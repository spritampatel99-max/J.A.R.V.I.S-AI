# Build the APK on GitHub (no Android Studio needed)

Why: the sandbox this project is written in cannot download Android's build libraries (Maven Central and Google Maven are blocked there), so the app has never been compiled by me. GitHub's own servers can. The workflow `.github/workflows/android.yml` does it for you.

## Steps (about 10 minutes of clicking, 15-25 minutes of waiting)
1. Unzip this project over your repository folder (the `.github` folder must be at the repository root, next to `android`).
2. Commit and push to a branch whose name starts with `ci/`, for example `ci/android-build`. (Or push anywhere and, on GitHub, open **Actions > Android build > Run workflow**.)
3. Open the **Actions** tab, click the newest "Android build" run.
4. When it is green, scroll to **Artifacts** and download `jarvis-3-debug-apk`. Unzip it and copy `app-debug.apk` to your phone. Allow "install unknown apps" for your file manager or browser, then tap the file.
5. If it is red, open the step that failed (usually "Build the app"). The errors are also written to a branch named `ci-logs` (files `ci-app.txt`, `ci-core.txt`, `RESULT.txt`). Send me that text and I will fix it.

## What the workflow does
Runs `:core:test`, builds `:app:assembleDebug`, runs Android lint, runs the 16 KB page-size check on the APK, and keeps the APK for 30 days. It uses no signing key and no secrets. The APK is a **debug** build (signed with Android's public debug key); it is for your own phone, not for the Play Store.

## Building on your own PC instead
Install Android Studio, open the `android` folder, let it sync, then Build > Build APK(s). The same errors appear in the Build window.
