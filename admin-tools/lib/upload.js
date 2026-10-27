// Writes the bundled catalog to Firestore as documents with origin "import". Safe to run again: a document the seed has
// not changed is left alone, and a document an admin has edited (origin "admin") is never overwritten unless asked.
const { FieldValue } = require('firebase-admin/firestore');

const COLLECTIONS = ['catalog_group', 'catalog_narrator', 'catalog_hadith'];
const BATCH_SIZE = 400;

/** Compares only what the seed owns, in a fixed key order. */
function same(wanted, stored) {
  const pick = (doc) => JSON.stringify(Object.keys(wanted).map((key) => doc[key] ?? null));
  return stored.deleted === false && pick(stored) === pick(wanted);
}

/**
 * @param db        a Firestore from the Admin SDK
 * @param catalog   the result of readSeed()
 * @returns what was done, per collection: { written, unchanged, keptAdmin, notInSeed }, plus the new revision
 */
async function uploadCatalog(db, catalog, { dryRun = false, overwriteAdmin = false } = {}) {
  const report = {};
  const writes = [];
  for (const name of COLLECTIONS) {
    const stored = new Map((await db.collection(name).get()).docs.map((d) => [d.id, d.data()]));
    const counts = { written: 0, unchanged: 0, keptAdmin: 0, notInSeed: 0 };
    const wantedIds = new Set();
    for (const wanted of catalog[name]) {
      const id = String(wanted.id);
      wantedIds.add(id);
      const current = stored.get(id);
      if (current && current.origin === 'admin' && !overwriteAdmin) {
        counts.keptAdmin++;
      } else if (current && same(wanted, current)) {
        counts.unchanged++;
      } else {
        counts.written++;
        writes.push({
          ref: db.collection(name).doc(id),
          data: { ...wanted, origin: 'import', updatedAt: FieldValue.serverTimestamp(), updatedBy: 'seed-upload', deleted: false },
        });
      }
    }
    counts.notInSeed = [...stored.keys()].filter((id) => !wantedIds.has(id)).length;
    report[name] = counts;
  }

  if (writes.length === 0 || dryRun) return { report, revision: null, dryRun };

  for (let i = 0; i < writes.length; i += BATCH_SIZE) {
    const batch = db.batch();
    writes.slice(i, i + BATCH_SIZE).forEach(({ ref, data }) => batch.set(ref, data));
    await batch.commit();
  }
  const meta = db.doc('meta/catalog');
  const revision = ((await meta.get()).data()?.revision ?? 0) + 1;
  await meta.set({ revision, updatedAt: FieldValue.serverTimestamp() });
  return { report, revision, dryRun };
}

module.exports = { uploadCatalog, COLLECTIONS };
