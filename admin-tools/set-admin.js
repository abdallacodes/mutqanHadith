#!/usr/bin/env node
// Makes an account an admin of the cloud catalog (or takes it away): sets the custom claim `admin` on the Firebase
// Authentication user with that email. The person must have signed in once, so the account exists.
//
//   node set-admin.js <email> --key <service-account.json>
//   node set-admin.js <email> --remove --key <service-account.json>
//
// The claim reaches the person's ID token the next time it refreshes: signing out and in again is enough.
const { getAuth } = require('firebase-admin/auth');
const { initialize, parseArgs } = require('./lib/firebase');

async function main() {
  const { positional, options } = parseArgs(process.argv.slice(2));
  const [email] = positional;
  if (!email) throw new Error('Usage: node set-admin.js <email> [--remove] [--key <service-account.json>]');
  initialize(options.key);
  const auth = getAuth();
  const user = await auth.getUserByEmail(email);
  const claims = { ...(user.customClaims ?? {}) };
  if (options.remove) delete claims.admin;
  else claims.admin = true;
  await auth.setCustomUserClaims(user.uid, claims);
  console.log(`${options.remove ? 'Removed admin from' : 'Made admin:'} ${email} (uid ${user.uid})`);
  console.log('It takes effect after the person signs out and in again.');
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
