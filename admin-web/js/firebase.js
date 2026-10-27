// The Firebase web SDK from Google's CDN, started with the configuration Firebase Hosting serves for this project's
// web app (so no keys live in this repository).
import { initializeApp } from 'https://www.gstatic.com/firebasejs/11.10.0/firebase-app.js';
import * as authSdk from 'https://www.gstatic.com/firebasejs/11.10.0/firebase-auth.js';
import * as fsSdk from 'https://www.gstatic.com/firebasejs/11.10.0/firebase-firestore.js';

export async function startFirebase() {
  const response = await fetch('/__/firebase/init.json');
  if (!response.ok) throw new Error('لم يتم العثور على إعدادات Firebase: سجّل تطبيق ويب للمشروع ثم انشر الصفحة.');
  const app = initializeApp(await response.json());
  return { auth: authSdk.getAuth(app), db: fsSdk.getFirestore(app), authSdk, fsSdk };
}
