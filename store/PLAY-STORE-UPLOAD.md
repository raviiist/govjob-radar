# GovJob Radar 1.6 — Google Play upload sheet

Everything to type or choose in Play Console, in order.

**Upload file:** `app-release.aab` from GitHub → Actions → **android** → latest green run → Artifacts →
`GovJobRadar-builds.zip` → `bundle/release/app-release.aab`. It is signed with your upload key
(`signing-key/govjobradar-upload.jks`, keep it private).
**Package:** com.dhruvaxtechnologies.govjobradar · **Version:** 1.6 (versionCode 7)

## 0. Before you start
- **Privacy policy URL (already public):** https://raviiist.github.io/govjob-radar/privacy.html
- **Developer contact email:** one you read regularly. It is shown on the store page and is the privacy contact.
- **Personal developer account rule:** new personal accounts must run a **closed test with at least 12 testers for 14 days in a row** before production access. Plan for this (friends, colleagues — they only need to install from the test link and keep it installed).

## 1. Create app
| Field | Value |
|---|---|
| App name | GovJob Radar |
| Default language | English (India) – en-IN |
| App or game | App |
| Free or paid | Free (the unlock is an in-app purchase) |
| Declarations | Tick Developer Program Policies + US export laws |

## 2. Testing → Internal testing (first)
1. Create release → accept **Play App Signing**.
2. Upload `app-release.aab`. Release name: `1.6 (7)`.
3. Release notes (en-IN):
   `First release: Central, State and Deputation government vacancies from official websites, updated every night, with eligibility check, last-date reminders and extension alerts.`
4. Add your Gmail as tester, roll out, install from the opt-in link.
5. **Important for Admin sign-in on the Play version:** Play Console → Test and release → **App integrity** → copy the **App signing key certificate SHA-1** → Firebase console → Project settings → your Android app → **Add fingerprint** → paste. (Your upload-key SHA-1 is already there.)

## 3. Monetize → Products → One-time products
| Field | Value |
|---|---|
| Product ID | `govjobradar_full_unlock` (must match exactly) |
| Name | GovJob Radar Full Unlock |
| Description | Full job details, eligibility check, saved jobs with reminders, morning alerts and search. |
| Price | your choice — suggestion ₹99–₹149 (job seekers are price-sensitive). Set it, then **Activate**. |

Setup → **License testing** → add your Gmail to test the purchase without being charged.

**What the trial does:** everything is open for 10 days from install (small, light "Free trial · N days left" line on the main screen). After that, job lists, last dates and the official-notification link stay free; full details, eligibility, saving/reminders, morning alerts and search need the one-time unlock.

## 4. Policy → App content
| Section | Answer |
|---|---|
| **Privacy policy** | https://raviiist.github.io/govjob-radar/privacy.html |
| **Ads** | No, my app does not contain ads |
| **App access** | All functionality is available without special access (the developer sign-in is hidden and not part of the user app) |
| **Content rating** | Category: *All other app types*. Answer **No** to violence, sexuality, language, drugs, gambling, user-to-user communication, sharing location. Digital purchases: **Yes**. Expected: Everyone / IARC 3+ |
| **Target audience** | **18 and over** only (job seekers). Not designed for children. |
| **News app** | No |
| **Government apps** | Not a government app. (If asked whether the app provides government information: yes — the disclaimer and official sources are in the store description and inside the app.) |
| **Financial features** | None |
| **Health** | None |
| **Data safety** | see section 5 |

## 5. Data safety form
*Does your app collect or share any of the required user data types?* → **Yes**
*Is all of the user data collected by your app encrypted in transit?* → **Yes**
*Do you provide a way for users to request that their data is deleted?* → **Yes** (by email to the developer contact)

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| App info and performance → **Crash logs** | Yes | No | No (automatic) | App functionality |
| App info and performance → **Diagnostics** | Yes | No | No (automatic) | App functionality |
| Device or other IDs → **Device or other IDs** (anonymous Firebase ID) | Yes | No | No | App functionality |
| Messages → **Other in-app messages** ("Report wrong information" text) | Yes | No | Yes | App functionality |

Not collected: name, email, phone, location, contacts, financial info (Google Play handles payments), profile details (date of birth, qualification, category stay on the phone only).
Data is **not** processed ephemerally; it is **not** shared with third parties (Google Firebase is our processor, not a third party sharing).

## 6. Store presence → Main store listing
**App name:** GovJob Radar

**Short description (80):**
Central, State & Deputation govt job alerts from official sites, updated nightly.

**Full description:**
GovJob Radar brings Indian government vacancies into one simple app — for job seekers and for serving government employees looking for deputation.

🏛️ CENTRAL, STATE AND DEPUTATION
• Central Government: ministries, defence, police & paramilitary, space & research, railways, banks, SSC, UPSC, PSUs, teaching, health and more
• State Government: choose your state and browse by sector
• Deputation: a separate section for serving employees, with pay-level filter and "near my level"

🌙 UPDATED EVERY NIGHT, AUTOMATICALLY
Every night official websites are checked for new advertisements, vacancy circulars, corrigenda and date extensions. Before a vacancy is removed after its last date, we check whether the date was extended.

✅ CHECKED AGAINST THE OFFICIAL NOTICE
Every listing comes only from official government websites and links straight to its official notification. Dates are cross-checked with the document, and listings whose official page has been removed are hidden.

🎯 MADE FOR YOU
• "Am I eligible?" — age with category relaxation, qualification, pay level for deputation
• Save jobs and get reminders 3 days and 1 day before the last date
• Alert when a saved job's last date is extended
• One morning summary of new jobs that match you — no late-night notifications
• Search, filters for closing soon / new / eligible

🔒 PRIVATE
Your profile stays on your phone. No ads. No sign-up.

Try everything free for 10 days, then unlock the full app with one purchase. No subscription.

SOURCES
Information is taken only from official websites of recruiting organisations and government departments, for example isro.gov.in, dos.gov.in, csir.res.in, barc.gov.in, crpf.gov.in, cisf.gov.in, kvsangathan.nic.in, indiapost.gov.in, opportunities.rbi.org.in, ibps.in, ncs.gov.in and State Public Service Commission websites. Each listing in the app shows and links to its own official source, and the full list of websites is in the app under Settings.

DISCLAIMER
GovJob Radar is NOT a government app and is not affiliated with, endorsed by, or representing any government, ministry, department or recruiting body. It is an independent app by DhruvaX Technologies. Information is collected automatically and summarised with the help of AI; we cannot guarantee 100% correctness. Always check the official notification before applying.

**Hindi short description (optional translation):**
केंद्र, राज्य व प्रतिनियुक्ति सरकारी नौकरी अलर्ट — आधिकारिक साइटों से, हर रात अपडेट।

**Graphics:**
| Asset | File / what to capture |
|---|---|
| App icon 512×512 | `store/play-icon-512.png` |
| Feature graphic 1024×500 | `store/feature-graphic-1024x500.png` |
| Phone screenshots (2–8, required) | Take on your phone (after a few jobs are live): (1) Central tab with the coloured header and sector tiles, (2) a job list, (3) a job detail page with "You look eligible", (4) Deputation tab, (5) State tab, (6) My Jobs → Saved with reminders, (7) the morning notification, (8) Unlock screen. Avoid showing your Admin icon — sign out first. |

**Categorisation:**
| Field | Value |
|---|---|
| App category | Business |
| Tags | Jobs, Government, Career |
| Contact email | your developer email |
| Website | https://raviiist.github.io/govjob-radar/ |

## 7. Closed testing → Production
1. Testing → **Closed testing** → create a track, upload the same AAB (or promote the internal release), add at least **12 testers** (email list or Google Group), share the opt-in link.
2. Keep it running **14 days**; testers should open the app a few times.
3. Then **Apply for production** in the Dashboard, answer the short questionnaire, and roll out to **India**.

## 8. Each later update
1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts` (GitHub builds the signed AAB on every push).
2. Upload the new `app-release.aab` to a new release.
