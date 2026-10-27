// Tests of the catalog part of ../firestore.rules against the Firestore emulator. Run with `npm test`.
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, deleteDoc, collection, getDocs, serverTimestamp } = require('firebase/firestore');

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'mutqan-hadith',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8') },
  });
});

after(async () => env.cleanup());

beforeEach(async () => env.clearFirestore());

const adminDb = () => env.authenticatedContext('boss', { admin: true }).firestore();
const notAdminDb = () => env.authenticatedContext('boss', { admin: false }).firestore();
const sara = () => env.authenticatedContext('sara').firestore();
const stranger = () => env.unauthenticatedContext().firestore();

const stamp = (extra = {}) => ({ origin: 'admin', updatedAt: serverTimestamp(), updatedBy: 'boss', deleted: false, ...extra });

const hadithDoc = (id, extra = {}) => ({
  id,
  matnWithTashkeel: 'إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ',
  sourceBook: 'الأربعون النووية',
  hadithNumber: 1,
  chapter: '',
  grade: '',
  collector: 'البخاري',
  takhrij: 'متفق عليه',
  sahabi: 'عمر',
  groupId: 1,
  position: 1,
  sanadSource: 'صحيح البخاري',
  sanad: [{ narratorId: 73, transmissionTerm: 'حدثنا' }],
  ...stamp(),
  ...extra,
});

const narratorDoc = (id, extra = {}) => ({
  id, primaryName: 'عمر', fullName: 'عمر بن الخطاب', tier: 'صحابي', isPivot: false, ...stamp(), ...extra,
});

const groupDoc = (id, extra = {}) => ({
  id, parentId: null, title: 'الأربعون النووية', titleEn: 'The Forty Hadith', position: 0, ...stamp(), ...extra,
});

test('a narrator may say whose duplicate it is, by number or by null, and may leave it out', async () => {
  const db = adminDb();
  await assertSucceeds(setDoc(doc(db, 'catalog_narrator/10000'), narratorDoc(10000, { sameAs: 73 })));
  await assertSucceeds(setDoc(doc(db, 'catalog_narrator/10001'), narratorDoc(10001, { sameAs: null })));
  await assertSucceeds(setDoc(doc(db, 'catalog_narrator/10002'), narratorDoc(10002)));
  await assertFails(setDoc(doc(db, 'catalog_narrator/10003'), narratorDoc(10003, { sameAs: '73' })));
  await assertFails(setDoc(doc(db, 'catalog_narrator/10004'), narratorDoc(10004, { sameAs: 73.5 })));
});

test('anyone reads the catalog, signed in or not', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'catalog_hadith/11'), hadithDoc(11, { updatedAt: new Date() }));
    await setDoc(doc(ctx.firestore(), 'meta/catalog'), { revision: 1, updatedAt: new Date() });
  });
  await assertSucceeds(getDoc(doc(stranger(), 'catalog_hadith/11')));
  await assertSucceeds(getDocs(collection(stranger(), 'catalog_hadith')));
  await assertSucceeds(getDoc(doc(sara(), 'catalog_hadith/11')));
  await assertSucceeds(getDoc(doc(stranger(), 'meta/catalog')));
});

test('only an admin writes the catalog', async () => {
  await assertSucceeds(setDoc(doc(adminDb(), 'catalog_hadith/11'), hadithDoc(11)));
  await assertSucceeds(setDoc(doc(adminDb(), 'catalog_narrator/73'), narratorDoc(73)));
  await assertSucceeds(setDoc(doc(adminDb(), 'catalog_group/1'), groupDoc(1)));
  await assertSucceeds(setDoc(doc(adminDb(), 'meta/catalog'), { revision: 2, updatedAt: serverTimestamp() }));

  await assertFails(setDoc(doc(sara(), 'catalog_hadith/12'), hadithDoc(12, { updatedBy: 'sara' })));
  await assertFails(setDoc(doc(notAdminDb(), 'catalog_hadith/12'), hadithDoc(12)));
  await assertFails(setDoc(doc(stranger(), 'catalog_hadith/12'), hadithDoc(12)));
  await assertFails(setDoc(doc(sara(), 'catalog_narrator/74'), narratorDoc(74, { updatedBy: 'sara' })));
  await assertFails(setDoc(doc(sara(), 'catalog_group/2'), groupDoc(2, { updatedBy: 'sara' })));
  await assertFails(setDoc(doc(sara(), 'meta/catalog'), { revision: 3, updatedAt: serverTimestamp() }));
});

test('an admin write says who made it and when, as an admin edit', async () => {
  const db = adminDb();
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { updatedBy: 'someone else' })));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { updatedAt: new Date(0) })));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { origin: 'import' })));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { deleted: 'no' })));
});

test('a catalog document is named by its id and carries exactly its fields', async () => {
  const db = adminDb();
  await assertFails(setDoc(doc(db, 'catalog_hadith/99'), hadithDoc(11)));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { extra: 1 })));
  const { grade, ...missing } = hadithDoc(11);
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), missing));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { matnWithTashkeel: '' })));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { hadithNumber: 'one' })));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { groupId: 'one' })));
  await assertSucceeds(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { groupId: null })));
});

test('a chain is a list of at most forty links', async () => {
  const db = adminDb();
  const link = { narratorId: 73, transmissionTerm: 'حدثنا' };
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { sanad: 'a chain' })));
  await assertFails(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { sanad: Array(41).fill(link) })));
  await assertSucceeds(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { sanad: Array(40).fill(link) })));
});

test('a narrator and a group are well formed too', async () => {
  const db = adminDb();
  await assertFails(setDoc(doc(db, 'catalog_narrator/73'), narratorDoc(73, { primaryName: '' })));
  await assertFails(setDoc(doc(db, 'catalog_narrator/73'), narratorDoc(73, { isPivot: 'yes' })));
  await assertFails(setDoc(doc(db, 'catalog_narrator/74'), narratorDoc(73)));
  await assertFails(setDoc(doc(db, 'catalog_group/1'), groupDoc(1, { title: '' })));
  await assertFails(setDoc(doc(db, 'catalog_group/1'), groupDoc(1, { parentId: 'x' })));
  await assertSucceeds(setDoc(doc(db, 'catalog_group/2'), groupDoc(2, { parentId: 1 })));
});

test('a catalog document is marked deleted, never removed', async () => {
  const db = adminDb();
  await assertSucceeds(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11)));
  await assertFails(deleteDoc(doc(db, 'catalog_hadith/11')));
  await assertSucceeds(setDoc(doc(db, 'catalog_hadith/11'), hadithDoc(11, { deleted: true })));
});

test('the history is added to by admins, read by admins, and never changed', async () => {
  const entry = (extra = {}) => ({
    collection: 'catalog_hadith', docId: '11', before: null, after: { grade: 'x' }, at: serverTimestamp(), by: 'boss', ...extra,
  });
  await assertSucceeds(setDoc(doc(adminDb(), 'catalog_history/e1'), entry()));
  await assertSucceeds(getDoc(doc(adminDb(), 'catalog_history/e1')));
  await assertFails(getDoc(doc(sara(), 'catalog_history/e1')));
  await assertFails(getDoc(doc(stranger(), 'catalog_history/e1')));
  await assertFails(setDoc(doc(sara(), 'catalog_history/e2'), entry({ by: 'sara' })));
  await assertFails(setDoc(doc(adminDb(), 'catalog_history/e1'), entry({ after: { grade: 'y' } })));
  await assertFails(deleteDoc(doc(adminDb(), 'catalog_history/e1')));
  await assertFails(setDoc(doc(adminDb(), 'catalog_history/e3'), entry({ by: 'someone else' })));
  await assertFails(setDoc(doc(adminDb(), 'catalog_history/e4'), entry({ collection: 'users' })));
});

test('an admin has no more access to a learner\'s progress than anyone else', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'users/sara/profile/main'), { startingPoint: 'EXPERT' });
  });
  await assertFails(getDoc(doc(adminDb(), 'users/sara/profile/main')));
});
