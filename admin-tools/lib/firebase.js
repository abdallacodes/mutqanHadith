// Admin SDK setup shared by the scripts. It acts with the authority of a service account, so it can do what no app can:
// it is how the first admin is made. The key file stays on the maintainer's machine, outside the repository.
const fs = require('node:fs');
const admin = require('firebase-admin');

const PROJECT_ID = 'mutqan-hadith';

/** `--key <file>` or the GOOGLE_APPLICATION_CREDENTIALS environment variable; under the emulator neither is needed. */
function initialize(keyFile) {
  if (process.env.FIRESTORE_EMULATOR_HOST) return admin.initializeApp({ projectId: PROJECT_ID });
  const file = keyFile ?? process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!file) {
    throw new Error('No service-account key: pass --key <file> or set GOOGLE_APPLICATION_CREDENTIALS (see README.md).');
  }
  return admin.initializeApp({ credential: admin.credential.cert(JSON.parse(fs.readFileSync(file, 'utf8'))), projectId: PROJECT_ID });
}

/** Splits `--key value` and `--flag` out of the arguments. */
function parseArgs(argv) {
  const positional = [];
  const options = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--key') options.key = argv[++i];
    else if (argv[i].startsWith('--')) options[argv[i].slice(2)] = true;
    else positional.push(argv[i]);
  }
  return { positional, options };
}

module.exports = { initialize, parseArgs, PROJECT_ID };
