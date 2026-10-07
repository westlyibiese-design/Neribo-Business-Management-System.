# NBMS — Firebase setup guide (phone-friendly)

Do these steps one at a time. Never paste keys or the service-account file into chat.

1. Open console.firebase.google.com → **Add project** → name it (for example `nbms-prod`) → turn Google Analytics **off** → Create.
2. Left menu → **Build → Authentication → Get started**. Do **not** enable any sign-in provider (custom tokens work without one). This step switches on the identity service that custom tokens need.
3. **Build → Firestore Database → Create database** → **Production mode** → pick the location closest to Nigeria that is offered (for example `europe-west1`, Belgium) → Enable.
4. **Build → Realtime Database → Create database** → choose a location → **Locked mode** → Enable. Do this **before** step 5 so the database address is included in the config file.
5. **Project settings (gear) → Your apps → Android icon** → Android package name **`com.westly.nbms`** → App nickname `NBMS` → Register → **Download `google-services.json`** → skip the remaining wizard steps.
6. Open the downloaded `google-services.json` in a text viewer, **select all and copy**. In GitHub: repository → Settings → Secrets and variables → Actions → New secret → name `GOOGLE_SERVICES_JSON` → paste → Save.
7. **Project settings → Service accounts → Generate new private key** → Generate. Open the downloaded JSON and copy everything. Create **two** secrets with that same text:
   - GitHub: `FIREBASE_SERVICE_ACCOUNT`
   - Supabase dashboard → **Edge Functions → Secrets → Add new secret**: `FIREBASE_SERVICE_ACCOUNT_JSON`
8. Also add GitHub secret `FIREBASE_PROJECT_ID` = the project id (Project settings → General → Project ID).
9. If you have the Realtime Database URL (Realtime Database page, for example `https://nbms-prod-default-rtdb.europe-west1.firebasedatabase.app`), add Supabase secret `FIREBASE_DATABASE_URL` with it.
10. Push your files, then GitHub → **Actions → "Deploy Firebase" → Run workflow**. Green means rules and indexes are live. If it fails with a permission error, in Google Cloud console → IAM give the service account the roles **Firebase Rules Admin** and **Cloud Datastore Index Admin**, then run it again.
