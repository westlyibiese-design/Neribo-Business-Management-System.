# NBMS (Neribo Business Management System)

Native Android app (Kotlin + Jetpack Compose) for hotel and business staff. This repository holds the whole project.
The app is **never built on a phone or laptop**: GitHub Actions builds the APK for you.

- Package name: `com.westly.nbms`
- Display name everywhere in the app: **NeriboBMS** (no spaces)
- Current state: **Phase 0** (project, build and look). The app opens on a temporary *Component gallery*.

## Get the APK

1. Open the repository on GitHub, then **Actions** and the newest **Android build** run.
2. Wait for the green tick, then open the run and scroll to **Artifacts**.
3. Download **nbms-debug-apk**, unzip it and install the `.apk` on your phone.

## Secrets (GitHub: Settings, Secrets and variables, Actions)

The build works with none of these. Add them when later phases tell you to.

| Secret | Used for |
|---|---|
| `GOOGLE_SERVICES_JSON` | Firebase config (the whole contents of `google-services.json`) |
| `SUPABASE_URL` | Supabase project URL |
| `SUPABASE_ANON_KEY` | Supabase anon (public) key |

Without them the build writes a placeholder `google-services.json` and uses placeholder Supabase values.

## Version matrix (known to work together)

| Tool | Version |
|---|---|
| Gradle | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (Compose compiler plugin = same version) |
| KSP | 2.0.21-1.0.28 |
| Hilt | 2.52 |
| Compose BOM | 2024.10.01 |
| google-services plugin | 4.4.2 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

All dependency versions live in `gradle/libs.versions.toml`. Later phases do not edit Gradle files.

## Fonts

Inter and Playfair Display are downloaded by the GitHub Actions build into `app/src/main/res/font/`
(they are not committed). If the download ever fails the app still builds and uses the system fonts.

## Release signing

`release` currently uses the debug signing key. A real keystore is set up in Phase 36.

## Layout

```
app/src/main/java/com/westly/nbms/
  MainActivity.kt, NbmsApplication.kt
  core/design/   theme and every shared component (Phase 0)
  core/util/     Format, Validators, Timestamps (Phase 0)
  core/session/  SessionModule (stub, replaced in Phase 3)
  shell/         StaffShell (stub, replaced in Phase 7)
```
