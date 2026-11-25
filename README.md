# GovJob Radar

Indian government vacancies — Central, State and Deputation — collected every night from official
websites, checked, and delivered to an Android app.

- `server/` – nightly crawler + AI reader (Gemini free tier) + verification + extension checks + feed builder
- `.github/workflows/` – `nightly` (crawl), `publish` (feed to GitHub Pages), `android` (builds APK/AAB)
- `android/` – the app (Kotlin, Jetpack Compose) with the developer-only Admin tab
- `firebase/firestore.rules` – database security rules

Setup: see [SETUP.md](SETUP.md).

**Not a government app.** GovJob Radar is independent and not affiliated with any government entity.
Every listing links to its official source.
