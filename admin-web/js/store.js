// Reads and writes the catalog for the admin page. The Firestore functions are handed in (`sdk`), so that the page
// passes the browser build and the tests pass the one in node_modules, against the emulator and the real rules.
import { COLLECTIONS, authored, sameContent } from './core.js';

export function createStore({ db, sdk, uid }) {
  const { collection, doc, getDocs, query, where, orderBy, limit, runTransaction, serverTimestamp } = sdk;

  async function loadAll() {
    const read = async (name) => (await getDocs(collection(db, name))).docs.map((d) => ({ ...d.data(), id: Number(d.id) }));
    const [hadiths, narrators, groups] = await Promise.all([
      read(COLLECTIONS.hadith), read(COLLECTIONS.narrator), read(COLLECTIONS.group),
    ]);
    return { hadiths, narrators, groups };
  }

  /**
   * Writes one document in a single transaction: the document itself, the catalog revision the phones look at first,
   * and a history entry holding what it was and what it became. A save that changes nothing writes nothing.
   * @returns {Promise<boolean>} whether anything was written
   */
  async function save(name, data, { isNew = false } = {}) {
    const after = authored(name, data);
    const ref = doc(db, name, String(after.id));
    const metaRef = doc(db, 'meta', 'catalog');
    return runTransaction(db, async (tx) => {
      const current = await tx.get(ref);
      const meta = await tx.get(metaRef);
      if (isNew && current.exists()) throw new Error('هذا الرقم مستخدم بالفعل، أعد تحميل الصفحة.');
      if (current.exists() && sameContent(name, current.data(), after)) return false;
      const before = current.exists() ? authored(name, current.data()) : null;
      const revision = meta.exists() && Number.isInteger(meta.data().revision) ? meta.data().revision + 1 : 1;
      tx.set(ref, { ...after, origin: 'admin', updatedAt: serverTimestamp(), updatedBy: uid });
      tx.set(metaRef, { revision, updatedAt: serverTimestamp() });
      tx.set(doc(collection(db, 'catalog_history')), {
        collection: name, docId: String(after.id), before, after, at: serverTimestamp(), by: uid,
      });
      return true;
    });
  }

  /** Deleting and restoring only flip `deleted`: nothing is ever removed, so either can be undone. */
  const setDeleted = (name, current, deleted) => save(name, { ...authored(name, current), deleted });

  const stamped = (snap) => snap.docs.map((d) => ({ ...d.data(), key: d.id }))
    .sort((a, b) => b.at.toMillis() - a.at.toMillis());

  /** The changes made to one document, newest first. */
  async function historyOf(name, id) {
    const q = query(collection(db, 'catalog_history'), where('collection', '==', name), where('docId', '==', String(id)));
    return stamped(await getDocs(q));
  }

  /** The latest changes to anything. */
  async function recentHistory(count = 50) {
    return stamped(await getDocs(query(collection(db, 'catalog_history'), orderBy('at', 'desc'), limit(count))));
  }

  /** Puts a document back as it was before the change in `entry`; a change that created it marks it deleted. */
  async function revert(entry, current) {
    if (entry.before) return save(entry.collection, entry.before);
    return setDeleted(entry.collection, current ?? entry.after, true);
  }

  return { loadAll, save, setDeleted, historyOf, recentHistory, revert };
}
