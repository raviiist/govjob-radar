# GovJob Radar — one-time setup (about 30 minutes, best done on a computer)

After these steps everything runs by itself every night. You never update links or data by hand.

```
02:11 IST  GitHub "nightly" job: crawls official sites → AI reads new notices → checks → Firestore
           ↳ then "publish" job: Firestore → jobs.json on GitHub Pages
every 2 h  "publish" again (so jobs you approve in Admin go live within ~2 h)
04:30–06:30 phones download the new list silently
08:00      phones show one summary notification if something new matches the user
```

---

## 1. GitHub (repo: raviiist/govjob-radar)

1. **Pages:** repo → Settings → Pages → *Build and deployment* → Source: **GitHub Actions**.
2. **Actions permissions:** Settings → Actions → General → *Allow all actions*; Workflow permissions: *Read*.
3. Secrets come in steps 2–4 below (Settings → Secrets and variables → Actions).

## 2. Firebase (free "Spark" plan is enough)

1. https://console.firebase.google.com → **Add project** → name `govjob-radar` (Google Analytics: off).
2. **Build → Authentication → Get started → Sign-in method**: enable **Google** and **Anonymous**.
   - Open Google → *Web SDK configuration* → copy the **Web client ID**.
3. **Build → Firestore Database → Create database** → location **asia-south1 (Mumbai)** → *Start in production mode*.
   - **Rules** tab: paste the whole of `firebase/firestore.rules`, replace `YOUR_ADMIN_GMAIL@gmail.com`
     with your Gmail, **Publish**.
4. **Project settings (gear) → Your apps → Android**:
   - Package name: `com.dhruvaxtechnologies.govjobradar`
   - SHA-1 (upload key, in `signing-key/KEY-DETAILS.txt`): add it here.
   - Register. You do **not** need to download google-services.json.
   - From *Project settings → General*, copy: **Project ID**, **App ID** (1:…:android:…),
     **Web API key**, and from *Cloud Messaging* the **Sender ID**.
5. **Project settings → Service accounts → Generate new private key** → a .json file downloads.
   Open it, copy everything → GitHub secret **`FIREBASE_SERVICE_ACCOUNT`**. Then delete the file.
6. Later, after the first Play upload: Play Console → *Test and release → App integrity* → copy the
   **App signing key SHA-1** and add it to the Firebase Android app too (otherwise Admin sign-in fails
   on the Play Store version).

## 3. Gemini API key (free tier)

1. https://aistudio.google.com → **Get API key** → *Create API key* (use your Google account).
2. GitHub secret **`GEMINI_API_KEY`**.
3. Optional GitHub *variable* (not secret) **`GEMINI_MODEL`** — leave unset to use `gemini-flash-latest`.
   If Google retires that name, Admin › Errors shows **AI_MODEL_NOT_FOUND**; set this variable to the
   current Flash model name shown in AI Studio.
4. Optional variable **`AI_MAX_CALLS`** (default 300 per night).

## 4. App config and signing

1. Edit `android/app-config.properties` (on GitHub: open the file → pencil icon) and fill in
   `ADMIN_EMAIL`, `FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_SENDER_ID`,
   `GOOGLE_WEB_CLIENT_ID`. Commit. (These values are not secret.)
2. Signing secrets (already set if Claude had access): `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. **Keep `signing-key/` safe and private — never upload it to
   the repo.** Losing it means you cannot update the app on Play.
3. Every commit to `android/` builds the app: Actions → **android** → latest run → *Artifacts* →
   `GovJobRadar-builds.zip` (release APK to install, AAB for Play).

## 5. First run

1. Actions → **nightly** → *Run workflow*. Takes 30–120 minutes the first time (it reads every source).
2. Actions → **publish** runs automatically after it. Feed: https://raviiist.github.io/govjob-radar/feed/v1/meta.json
3. Install the release APK → Settings → **Developer sign-in** → your Gmail → the Admin (shield) icon appears.
4. **First week:** everything from each source goes to Admin › Review (safety rule — switch it off in
   Admin › Health › Publishing rules when you trust it). After that only notices that fail a check wait for you.

---

## What the Admin tab shows

| Tab | What it is |
|---|---|
| **Review** | Notices that failed a check (date not found verbatim, low AI confidence, non-government apply link, new source…) and possible date extensions. Approve / Fix / Reject. |
| **Errors** | Everything that should have happened but didn't: source failing 3 nights, page moved (found automatically), GitHub job failed (with link to the run), AI key/model/quota, feed not published or shrank sharply, official link broken, and from phones: nightly update failed or stopped, old feed, crashes, notifications failing, users' "wrong information" reports. Severity-coloured, grouped, with "What to do". |
| **Health** | Green/red for the nightly crawl (red if no success in 30 h — this also catches a GitHub job that could not even start) and the publish; last night's numbers; each source's status; publishing switches. |
| **Live** | Every live job; *Take down* hides one at the next publish. |

## Rules the server follows (accuracy)

- Only links on the source's own domain or official suffixes (.gov.in, .nic.in, .ac.in, .res.in …) are used; anything else is dropped.
- Auto-publish only if: last date found **verbatim** in the document and it matches, advertisement number found in the text, AI confidence ≥ 0.75, apply link on a government domain, not a new source. Otherwise → Review.
- Scanned PDFs (image only) always go to Review.
- **Before removing an expired job:** corrigenda on the source page are read the same night; the apply page is checked for a later "last date"; the job stays as "checking for extension" for 3 nights. Only then is it closed (kept 30 days in *Closed*).
- Official link checked every night; hidden after 2 failed nights, comes back automatically when it works.
- A publish that would drop more than half of the jobs at once is refused (users keep yesterday's list) and flagged.

## If many sites block GitHub's servers

Some Indian government sites refuse foreign IP addresses. If Admin › Errors shows many `SOURCE_FAILING … BLOCKED`,
run the nightly job from India instead: repo → Settings → Actions → Runners → *New self-hosted runner* (any always-on
PC or Raspberry Pi at home), then change `runs-on: ubuntu-latest` to `runs-on: self-hosted` in
`.github/workflows/nightly.yml` (crawl job only).

## Adding or removing a source

Edit `server/config/sources.yaml` (one line per source). You never need to fix page links: if a site moves its
recruitment page, the crawler finds it again from the home page and tells you (SOURCE_MOVED, info only).
