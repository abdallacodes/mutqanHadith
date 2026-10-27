#!/usr/bin/env node
// Uploads the bundled catalog (app/src/main/assets/seed_hadiths.json) to Firestore, as documents with origin "import".
//
//   node upload-seed.js --key <service-account.json> [--dry-run] [--overwrite-admin]
//
// Run it once to start the cloud catalog, and again after regenerating the seed with tools/import_hadiths.py: documents
// the seed has not changed are left alone, and documents an admin has edited are kept unless --overwrite-admin is given.
const { getFirestore } = require('firebase-admin/firestore');
const { initialize, parseArgs } = require('./lib/firebase');
const { readSeed } = require('./lib/seed');
const { uploadCatalog } = require('./lib/upload');

async function main() {
  const { options } = parseArgs(process.argv.slice(2));
  initialize(options.key);
  const catalog = readSeed();
  const result = await uploadCatalog(getFirestore(), catalog, {
    dryRun: options['dry-run'] === true,
    overwriteAdmin: options['overwrite-admin'] === true,
  });
  console.log(`Seed catalog version ${catalog.catalogVersion}${result.dryRun ? ' (dry run, nothing written)' : ''}`);
  for (const [name, counts] of Object.entries(result.report)) {
    console.log(`  ${name}: ${counts.written} written, ${counts.unchanged} unchanged, ${counts.keptAdmin} kept (edited by an admin), ${counts.notInSeed} not in the seed`);
  }
  if (result.revision !== null) console.log(`meta/catalog revision is now ${result.revision}`);
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
