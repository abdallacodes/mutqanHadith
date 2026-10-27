# Admin tools

Maintainer scripts for the Firebase project `mutqan-hadith`. They act with a **service-account key**, which can do
anything in the project: keep that file on your machine, **outside this repository**, and never commit or share it.

## One-time setup

1. **Get a key.** Firebase console → Project settings (gear) → *Service accounts* → *Generate new private key*. Save the
   file somewhere outside the repo, for example `C:\Users\<you>\secrets\mutqan-hadith-admin.json`.
   (If the console refuses to create keys, an organization policy is blocking it; sign in with `gcloud auth
   application-default login` and set `GOOGLE_APPLICATION_CREDENTIALS` to the file it writes instead.)
2. `cd admin-tools` and `npm install`.
3. **Publish the rules** from the repository root: `firebase deploy --only firestore:rules`.

## Make an admin

The person signs in to the app once (so their account exists), then:

```
node set-admin.js someone@example.com --key C:\path\to\key.json
node set-admin.js someone@example.com --remove --key C:\path\to\key.json
```

The claim reaches them after they sign out and in again. The email is only ever an argument here, never in the code.

## Upload the bundled catalog

```
node upload-seed.js --key C:\path\to\key.json --dry-run     # shows what would be written
node upload-seed.js --key C:\path\to\key.json
```

It writes every group, narrator and hadith of `app/src/main/assets/seed_hadiths.json` as a document with
`origin: "import"` and raises `meta/catalog.revision`, which is what makes the apps pull. Running it again is safe:
documents the seed has not changed are left alone, and documents an admin has edited (`origin: "admin"`) are kept unless
you pass `--overwrite-admin`. After regenerating the seed with `tools/import_hadiths.py`, run it again to publish the
correction.

## Tests

`npm test` runs the upload against the Firestore emulator (Java and the Firebase CLI are needed, as for
`firebase-tests/`).
