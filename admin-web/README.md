# Admin page

A static page (no build step) for editing the cloud catalog: hadiths with their chains, narrators and groups, with a
history of every change and a way to put one back. It is deployed with Firebase Hosting and writes to Firestore as the
signed-in Google account, which must carry the `admin` claim (`admin-tools/set-admin.js`). The rules in
`firestore.rules` are what stop anyone else; the page only makes the checks friendly.

## One-time setup
1. Firebase console → Project settings → General → **Add app → Web** (the nickname can be "admin"). You do not need
   to copy the config it shows: Hosting serves it to the page at `/__/firebase/init.json`.
2. Firebase console → Authentication → Settings → Authorized domains: `mutqan-hadith.web.app` and
   `mutqan-hadith.firebaseapp.com` are there by default.
3. Deploy: `firebase deploy --only hosting` (and `--only firestore:rules` if the rules changed). The page is at
   `https://mutqan-hadith.web.app`.

## Using it
- Sign in with the admin account. Someone else gets "this account is not authorized".
- **Hadiths**: search the text, filter by group, open one to edit it. The chain is an ordered list from the
  collector's side to the Companion; pick narrators by name or number. A narrator that does not exist yet is made first
  under **Narrators** («راوٍ جديد»), then «تحديث قائمة الرواة» in the editor.
- **Delete** only marks the document; **restore** brings it back. A deleted hadith leaves every phone on its next
  library check, together with that phone's progress on it (the account copy stays, so a restore brings it back).
- **History** lists the latest changes; every editor shows its own document's changes. **تراجع** writes the older
  version again as a new change (reverting a creation deletes the document).
- New ids start at 10000.
- **Same person under another name:** a narrator made here (or any narrator) can be marked «هو نفسه الراوي…» pointing at
  the one that stays. The app (from v1.8.0) then reads the two as one person in drills, the narrator network and
  the library, under the name of the one that stays; nobody's progress is rewritten, and clearing the field separates
  them again. The one that stays cannot itself be pointed elsewhere, and a narrator others point at cannot be
  deleted or pointed away; the page enforces both. Older apps ignore the field.
- **About the Companion:** a narrator has a «نبذة عن الراوي» box. Paragraphs are separated by a blank line. In the app
  (from v1.8.0) a hadith whose Companion has a bio shows a folded «نبذة عن الصحابي» card on its study screen; empty means
  no card. Nothing is filled in by default. The text is read from the Companion's row, or from a row marked as the same
  person (above). At most 20000 characters; the rules enforce it. Older apps ignore the field.
- Phones pick changes up when the app opens (at most every 6 hours), on pull-down in Home, from Settings → المكتبة, and
  about every 12 hours in the background.

## Code
`js/core.js` (what is valid, ids, ordering), `js/store.js` (reads and the one transaction per save: document + catalog
revision + history entry), `js/firebase.js` (SDK from the CDN), `js/app.js` (screens). `firebase-tests/admin-web.test.js`
tests core and store against the emulator with the real rules (`cd firebase-tests && npm test`, needs Java).
