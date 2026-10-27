// Tests of ../firestore.rules against the Firestore emulator. Run with `npm test` (it starts the emulator itself).
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

const sara = () => env.authenticatedContext('sara').firestore();
const omar = () => env.authenticatedContext('omar').firestore();
const stranger = () => env.unauthenticatedContext().firestore();

const mastery = (hadithId, extra = {}) => ({
  hadithId,
  matnStability: 4.5,
  sanadStability: 1,
  matnLastReviewedAt: 1700000000000,
  sanadLastReviewedAt: null,
  nextReviewDue: 1700086400000,
  repetitionCount: 2,
  ...extra,
});

const event = (hadithId, narratorId, reviewedAt, extra = {}) => ({
  hadithId,
  narratorId,
  reviewedAt,
  succeeded: true,
  uploadedAt: serverTimestamp(),
  ...extra,
});

// -- Whose data it is ---------------------------------------------------------------------------

test('a learner writes and reads their own mastery records', async () => {
  const db = sara();
  await assertSucceeds(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1)));
  const snap = await assertSucceeds(getDoc(doc(db, 'users/sara/mastery/1')));
  if (snap.data().repetitionCount !== 2) throw new Error('record not stored as written');
  await assertSucceeds(getDocs(collection(db, 'users/sara/mastery')));
});

test('nobody else reads or writes another learner\'s progress', async () => {
  await assertSucceeds(setDoc(doc(sara(), 'users/sara/mastery/1'), mastery(1)));
  await assertFails(getDoc(doc(omar(), 'users/sara/mastery/1')));
  await assertFails(getDocs(collection(omar(), 'users/sara/mastery')));
  await assertFails(setDoc(doc(omar(), 'users/sara/mastery/2'), mastery(2)));
  await assertFails(deleteDoc(doc(omar(), 'users/sara/mastery/1')));
});

test('someone signed out reads and writes nothing', async () => {
  await assertSucceeds(setDoc(doc(sara(), 'users/sara/mastery/1'), mastery(1)));
  await assertFails(getDoc(doc(stranger(), 'users/sara/mastery/1')));
  await assertFails(setDoc(doc(stranger(), 'users/sara/mastery/2'), mastery(2)));
});

test('nothing outside users/{uid} is open', async () => {
  await assertFails(setDoc(doc(sara(), 'elsewhere/x'), { a: 1 }));
  await assertFails(getDoc(doc(sara(), 'catalog/hadith-1')));
  await assertFails(setDoc(doc(sara(), 'users/sara/other/x'), { a: 1 }));
  await assertFails(setDoc(doc(sara(), 'users/sara'), { a: 1 }));
});

// -- The shape of a mastery record --------------------------------------------------------------

test('a mastery record must carry exactly its fields', async () => {
  const db = sara();
  await assertFails(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1, { extra: 'x' })));
  const { repetitionCount, ...missing } = mastery(1);
  await assertFails(setDoc(doc(db, 'users/sara/mastery/1'), missing));
});

test('a mastery record must hold numbers where numbers belong', async () => {
  const db = sara();
  await assertFails(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1, { matnStability: 'high' })));
  await assertFails(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1, { repetitionCount: 'many' })));
  await assertFails(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1, { matnLastReviewedAt: 'yesterday' })));
});

test('a mastery record may leave a review time empty', async () => {
  await assertSucceeds(
    setDoc(doc(sara(), 'users/sara/mastery/1'), mastery(1, { matnLastReviewedAt: null, sanadLastReviewedAt: null })),
  );
});

test('a mastery record belongs under its own hadith id', async () => {
  await assertFails(setDoc(doc(sara(), 'users/sara/mastery/7'), mastery(1)));
});

test('a mastery record can be replaced and deleted by its owner', async () => {
  const db = sara();
  await assertSucceeds(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1)));
  await assertSucceeds(setDoc(doc(db, 'users/sara/mastery/1'), mastery(1, { repetitionCount: 3 })));
  await assertSucceeds(deleteDoc(doc(db, 'users/sara/mastery/1')));
});

// -- Narrator events ----------------------------------------------------------------------------

test('a narrator event is named by its hadith, narrator and time, and stamped by the server', async () => {
  const db = sara();
  await assertSucceeds(setDoc(doc(db, 'users/sara/events/1_10_1700000000000'), event(1, 10, 1700000000000)));
  await assertFails(setDoc(doc(db, 'users/sara/events/wrong-name'), event(1, 10, 1700000000000)));
  await assertFails(
    setDoc(doc(db, 'users/sara/events/1_10_1700000000001'), event(1, 10, 1700000000001, { uploadedAt: new Date(0) })),
  );
});

test('a narrator event must be well formed', async () => {
  const db = sara();
  await assertFails(setDoc(doc(db, 'users/sara/events/1_10_5'), event(1, 10, 5, { succeeded: 'yes' })));
  await assertFails(setDoc(doc(db, 'users/sara/events/1_10_5'), event(1, 10, 5, { extra: 1 })));
});

test('only the owner reads narrator events', async () => {
  await assertSucceeds(setDoc(doc(sara(), 'users/sara/events/1_10_5'), event(1, 10, 5)));
  await assertSucceeds(getDocs(collection(sara(), 'users/sara/events')));
  await assertFails(getDocs(collection(omar(), 'users/sara/events')));
});

// -- The starting point -------------------------------------------------------------------------

test('the starting point is one of the three', async () => {
  const db = sara();
  await assertSucceeds(setDoc(doc(db, 'users/sara/profile/main'), { startingPoint: 'EXPERT' }));
  await assertFails(setDoc(doc(db, 'users/sara/profile/main'), { startingPoint: 'GENIUS' }));
  await assertFails(setDoc(doc(db, 'users/sara/profile/main'), { startingPoint: 'EXPERT', admin: true }));
  await assertFails(getDoc(doc(omar(), 'users/sara/profile/main')));
});
