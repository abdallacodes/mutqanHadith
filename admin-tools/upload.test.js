// Tests of the catalog upload against the Firestore emulator. Run with `npm test` (it starts the emulator itself).
const { test, before, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const admin = require('firebase-admin');
const { getFirestore } = require('firebase-admin/firestore');
const { readSeed } = require('./lib/seed');
const { uploadCatalog } = require('./lib/upload');

let db;

before(() => {
  admin.initializeApp({ projectId: 'mutqan-hadith' });
  db = getFirestore();
});

beforeEach(async () => {
  for (const name of ['catalog_group', 'catalog_narrator', 'catalog_hadith', 'meta']) {
    const docs = await db.collection(name).get();
    await Promise.all(docs.docs.map((d) => d.ref.delete()));
  }
});

const revision = async () => (await db.doc('meta/catalog').get()).data()?.revision;

// -- The seed as documents ----------------------------------------------------------------------

test('every hadith of the seed becomes a document the rules accept the shape of', () => {
  const catalog = readSeed();
  assert.equal(catalog.catalog_hadith.length, 42);
  assert.ok(catalog.catalog_narrator.length > 100);
  assert.equal(catalog.catalog_group.length, 1);
  const narratorIds = new Set(catalog.catalog_narrator.map((n) => n.id));
  for (const h of catalog.catalog_hadith) {
    assert.ok(Number.isInteger(h.id) && h.matnWithTashkeel.length > 0);
    assert.ok(h.sanad.length >= 1 && h.sanad.length <= 40, `hadith ${h.id}: chain length`);
    for (const link of h.sanad) {
      assert.ok(narratorIds.has(link.narratorId), `hadith ${h.id}: unknown narrator ${link.narratorId}`);
      assert.equal(typeof link.transmissionTerm, 'string');
    }
    assert.ok(h.groupId === null || Number.isInteger(h.groupId));
    assert.deepEqual(Object.keys(h).sort(), [
      'chapter', 'collector', 'grade', 'groupId', 'hadithNumber', 'id', 'matnWithTashkeel', 'position', 'sahabi',
      'sanad', 'sanadSource', 'sourceBook', 'takhrij',
    ]);
  }
});

// -- Uploading ----------------------------------------------------------------------------------

test('the first upload writes the whole catalog as imports and starts the revision at 1', async () => {
  const catalog = readSeed();

  const { report, revision: made } = await uploadCatalog(db, catalog);

  assert.equal(report.catalog_hadith.written, 42);
  assert.equal(report.catalog_narrator.written, catalog.catalog_narrator.length);
  assert.equal(report.catalog_group.written, 1);
  assert.equal(made, 1);
  assert.equal(await revision(), 1);
  const stored = (await db.collection('catalog_hadith').doc('11').get()).data();
  assert.equal(stored.origin, 'import');
  assert.equal(stored.deleted, false);
  assert.equal(stored.updatedBy, 'seed-upload');
  assert.ok(stored.updatedAt.toDate() instanceof Date);
  assert.equal(stored.sanad.length, 6);
  assert.equal((await db.collection('catalog_hadith').get()).size, 42);
});

test('uploading again changes nothing and leaves the revision alone', async () => {
  const catalog = readSeed();
  await uploadCatalog(db, catalog);

  const { report, revision: made } = await uploadCatalog(db, catalog);

  assert.equal(report.catalog_hadith.written, 0);
  assert.equal(report.catalog_hadith.unchanged, 42);
  assert.equal(made, null);
  assert.equal(await revision(), 1);
});

test('a changed hadith is the only one written, and the revision moves on', async () => {
  const catalog = readSeed();
  await uploadCatalog(db, catalog);
  catalog.catalog_hadith[3].grade = 'صحيح';

  const { report } = await uploadCatalog(db, catalog);

  assert.equal(report.catalog_hadith.written, 1);
  assert.equal(report.catalog_hadith.unchanged, 41);
  assert.equal(await revision(), 2);
  const id = String(catalog.catalog_hadith[3].id);
  assert.equal((await db.collection('catalog_hadith').doc(id).get()).data().grade, 'صحيح');
});

test('a hadith an admin edited is kept, unless the upload is told to overwrite it', async () => {
  const catalog = readSeed();
  await uploadCatalog(db, catalog);
  const ref = db.collection('catalog_hadith').doc('11');
  await ref.update({ grade: 'edited by an admin', origin: 'admin' });

  const kept = await uploadCatalog(db, catalog);
  assert.equal(kept.report.catalog_hadith.keptAdmin, 1);
  assert.equal((await ref.get()).data().grade, 'edited by an admin');

  const overwritten = await uploadCatalog(db, catalog, { overwriteAdmin: true });
  assert.equal(overwritten.report.catalog_hadith.written, 1);
  const after = (await ref.get()).data();
  assert.equal(after.grade, '');
  assert.equal(after.origin, 'import');
});

test('a hadith an admin deleted is not brought back by the upload', async () => {
  const catalog = readSeed();
  await uploadCatalog(db, catalog);
  await db.collection('catalog_hadith').doc('12').update({ deleted: true, origin: 'admin' });

  await uploadCatalog(db, catalog);

  assert.equal((await db.collection('catalog_hadith').doc('12').get()).data().deleted, true);
});

test('a dry run reports and writes nothing', async () => {
  const { report, revision: made, dryRun } = await uploadCatalog(db, readSeed(), { dryRun: true });

  assert.equal(dryRun, true);
  assert.equal(report.catalog_hadith.written, 42);
  assert.equal(made, null);
  assert.equal((await db.collection('catalog_hadith').get()).size, 0);
  assert.equal(await revision(), undefined);
});

test('documents the seed no longer has are reported and left alone', async () => {
  const catalog = readSeed();
  await uploadCatalog(db, catalog);
  catalog.catalog_hadith.pop();

  const { report } = await uploadCatalog(db, catalog);

  assert.equal(report.catalog_hadith.notInSeed, 1);
  assert.equal((await db.collection('catalog_hadith').get()).size, 42);
});
