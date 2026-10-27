// Tests of the admin page's logic (admin-web/js/core.js) and of its store (admin-web/js/store.js) against the Firestore
// emulator with the real firestore.rules, so that what the page writes is what the rules accept. Run with `npm test`.
const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertFails } = require('@firebase/rules-unit-testing');
const sdk = require('firebase/firestore');

let env;
let core;
let createStore;

before(async () => {
  core = await import('../admin-web/js/core.js');
  ({ createStore } = await import('../admin-web/js/store.js'));
  env = await initializeTestEnvironment({
    projectId: 'mutqan-hadith',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8') },
  });
});

after(async () => env.cleanup());

beforeEach(async () => env.clearFirestore());

const storeAs = (uid, claims) => createStore({ db: env.authenticatedContext(uid, claims).firestore(), sdk, uid });
const admin = () => storeAs('boss', { admin: true });

const narrator = (id, extra = {}) => ({ id, primaryName: `راوٍ ${id}`, fullName: `راوٍ ${id}`, tier: 'راوٍ', isPivot: false, deleted: false, ...extra });
const group = (id, extra = {}) => ({ id, parentId: null, title: 'مجموعة', titleEn: '', position: 0, deleted: false, ...extra });
const hadith = (id, extra = {}) => ({
  id,
  matnWithTashkeel: 'إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ',
  sourceBook: 'الأربعون النووية',
  hadithNumber: 1,
  chapter: '',
  grade: '',
  collector: 'البخاري',
  takhrij: '',
  sahabi: 'عمر',
  groupId: null,
  position: 1,
  sanadSource: '',
  sanad: [{ narratorId: 73, transmissionTerm: 'حدثنا' }],
  deleted: false,
  ...extra,
});

const ctx = (extra = {}) => ({
  narrators: [narrator(73), narrator(74), narrator(75, { deleted: true })],
  groups: [group(1), group(2, { parentId: 1 })],
  ...extra,
});

// ---- the rules the page enforces before it writes ----

test('a hadith with text, a chain of known narrators and a real group is valid', () => {
  assert.deepEqual(core.validateHadith(hadith(10000, { groupId: 1 }), ctx()), []);
});

test('a blank matn, an empty chain, an unknown or deleted narrator and a missing group are each refused', () => {
  assert.equal(core.validateHadith(hadith(1, { matnWithTashkeel: '  \n' }), ctx()).length, 1);
  assert.equal(core.validateHadith(hadith(1, { sanad: [] }), ctx()).length, 1);
  assert.equal(core.validateHadith(hadith(1, { sanad: [{ narratorId: 999, transmissionTerm: '' }] }), ctx()).length, 1);
  assert.equal(core.validateHadith(hadith(1, { sanad: [{ narratorId: NaN, transmissionTerm: '' }] }), ctx()).length, 1);
  assert.equal(core.validateHadith(hadith(1, { sanad: [{ narratorId: 75, transmissionTerm: '' }] }), ctx()).length, 1);
  assert.equal(core.validateHadith(hadith(1, { groupId: 99 }), ctx()).length, 1);
  assert.equal(core.validateHadith(hadith(1, { hadithNumber: 1.5 }), ctx()).length, 1);
});

test('a chain longer than 40 is refused', () => {
  const sanad = Array.from({ length: 41 }, () => ({ narratorId: 73, transmissionTerm: '' }));
  assert.equal(core.validateHadith(hadith(1, { sanad }), ctx()).length, 1);
});

test('a narrator needs a name and a tier; a group needs a title and may not sit inside itself', () => {
  assert.deepEqual(core.validateNarrator(narrator(1)), []);
  assert.equal(core.validateNarrator(narrator(1, { primaryName: ' ' })).length, 1);
  assert.equal(core.validateNarrator(narrator(1, { tier: 'x' })).length, 1);
  const groups = [group(1), group(2, { parentId: 1 }), group(3, { parentId: 2 })];
  assert.deepEqual(core.validateGroup(group(1, { parentId: null }), groups), []);
  assert.equal(core.validateGroup(group(1, { parentId: 3 }), groups).length, 1);
  assert.equal(core.validateGroup(group(2, { parentId: 2 }), groups).length, 1);
  assert.equal(core.validateGroup(group(4, { title: '' }), groups).length, 1);
});

test('new ids start at 10000 and continue after the highest', () => {
  assert.equal(core.nextId([{ id: 11 }, { id: 52 }]), 10000);
  assert.equal(core.nextId([{ id: 11 }, { id: 10004 }]), 10005);
  assert.equal(core.nextId([]), 10000);
});

test('who uses a narrator, and the groups in reading order', () => {
  const hadiths = [hadith(1), hadith(2, { deleted: true }), hadith(3, { sanad: [{ narratorId: 74, transmissionTerm: '' }] })];
  assert.deepEqual(core.hadithsUsing(hadiths, 73).map((h) => h.id), [1]);
  const rows = core.groupRows([group(3, { parentId: 1, position: 2 }), group(2, { parentId: 1, position: 1 }), group(1)]);
  assert.deepEqual(rows.map((r) => [r.group.id, r.depth]), [[1, 0], [2, 1], [3, 1]]);
  assert.equal(core.idFromLabel('73 — الحميدي (راوٍ)'), 73);
  assert.ok(Number.isNaN(core.idFromLabel('الحميدي')));
});

test('a changed field is named, an unchanged document is the same content', () => {
  const a = hadith(1);
  const b = hadith(1, { grade: 'صحيح' });
  assert.deepEqual(core.changedFields('catalog_hadith', a, b), ['grade']);
  assert.ok(core.sameContent('catalog_hadith', a, { ...a, origin: 'import', updatedBy: 'x' }));
  assert.ok(core.changedFields('catalog_hadith', null, a).includes('matnWithTashkeel'));
});

// ---- what the store writes, accepted by the real rules ----

test('saving a new hadith writes it, bumps the revision and records the change', async () => {
  const store = admin();
  assert.equal(await store.save('catalog_hadith', hadith(10000), { isNew: true }), true);

  const { hadiths } = await store.loadAll();
  assert.equal(hadiths.length, 1);
  assert.equal(hadiths[0].origin, 'admin');
  assert.equal(hadiths[0].updatedBy, 'boss');
  assert.equal(hadiths[0].id, 10000);

  await env.withSecurityRulesDisabled(async (c) => {
    const meta = await sdk.getDoc(sdk.doc(c.firestore(), 'meta/catalog'));
    assert.equal(meta.data().revision, 1);
  });
  const history = await store.historyOf('catalog_hadith', 10000);
  assert.equal(history.length, 1);
  assert.equal(history[0].before, null);
  assert.equal(history[0].after.id, 10000);
  assert.equal(history[0].by, 'boss');
});

test('each save raises the revision by one, and a save that changes nothing writes nothing', async () => {
  const store = admin();
  await store.save('catalog_hadith', hadith(10000), { isNew: true });
  assert.equal(await store.save('catalog_hadith', hadith(10000, { grade: 'صحيح' })), true);
  assert.equal(await store.save('catalog_hadith', hadith(10000, { grade: 'صحيح' })), false);

  await env.withSecurityRulesDisabled(async (c) => {
    const meta = await sdk.getDoc(sdk.doc(c.firestore(), 'meta/catalog'));
    assert.equal(meta.data().revision, 2);
  });
  assert.equal((await store.historyOf('catalog_hadith', 10000)).length, 2);
});

test('a new document does not overwrite one that is already there', async () => {
  const store = admin();
  await store.save('catalog_narrator', narrator(10000), { isNew: true });
  await assert.rejects(store.save('catalog_narrator', narrator(10000, { primaryName: 'غيره' }), { isNew: true }));
  assert.equal((await store.loadAll()).narrators[0].primaryName, 'راوٍ 10000');
});

test('narrators and groups save under the rules too', async () => {
  const store = admin();
  await store.save('catalog_narrator', narrator(10000), { isNew: true });
  await store.save('catalog_group', group(10000), { isNew: true });
  const all = await store.loadAll();
  assert.equal(all.narrators.length, 1);
  assert.equal(all.groups.length, 1);
});

test('deleting marks the document and restoring brings it back', async () => {
  const store = admin();
  await store.save('catalog_hadith', hadith(10000), { isNew: true });
  const [saved] = (await store.loadAll()).hadiths;

  await store.setDeleted('catalog_hadith', saved, true);
  assert.equal((await store.loadAll()).hadiths[0].deleted, true);
  await store.setDeleted('catalog_hadith', (await store.loadAll()).hadiths[0], false);
  assert.equal((await store.loadAll()).hadiths[0].deleted, false);
});

test('reverting puts back what a change replaced; reverting a creation deletes it', async () => {
  const store = admin();
  await store.save('catalog_hadith', hadith(10000), { isNew: true });
  await store.save('catalog_hadith', hadith(10000, { grade: 'ضعيف', matnWithTashkeel: 'نص آخر' }));

  const history = await store.historyOf('catalog_hadith', 10000);
  assert.equal(history.length, 2);
  const [latest, creation] = history;
  assert.equal(latest.after.grade, 'ضعيف');

  await store.revert(latest, (await store.loadAll()).hadiths[0]);
  const reverted = (await store.loadAll()).hadiths[0];
  assert.equal(reverted.grade, '');
  assert.equal(reverted.matnWithTashkeel, 'إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ');
  assert.equal((await store.historyOf('catalog_hadith', 10000)).length, 3, 'the revert is itself a recorded change');

  await store.revert(creation, (await store.loadAll()).hadiths[0]);
  assert.equal((await store.loadAll()).hadiths[0].deleted, true);
});

test('the recent changes list newest first', async () => {
  const store = admin();
  await store.save('catalog_narrator', narrator(10000), { isNew: true });
  await store.save('catalog_group', group(10000), { isNew: true });
  const recent = await store.recentHistory(10);
  assert.deepEqual(recent.map((e) => e.collection), ['catalog_group', 'catalog_narrator']);
});

test('someone who is not an admin cannot save, and nothing is written', async () => {
  const reader = storeAs('sara', {});
  await assert.rejects(reader.save('catalog_hadith', hadith(10000), { isNew: true }));
  const rows = await admin().loadAll();
  assert.equal(rows.hadiths.length, 0);
  await assertFails(sdk.getDocs(sdk.collection(env.authenticatedContext('sara').firestore(), 'catalog_history')));
});
