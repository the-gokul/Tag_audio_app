# Build the APK on GitHub

This repo builds a **debug APK** on every push to `main` (and on **Actions → Build APK → Run workflow**).

## Download the APK

1. Open [Actions](https://github.com/the-gokul/Tag_audio_app/actions).
2. Open the latest **Build APK** run.
3. Download the artifact **Tag-audio-debug**.
4. Unzip it and install `app-debug.apk` on the phone (enable unknown sources / debug install).

The app module writes outputs to `build-app-timing/` (not `app/build/`). The workflow already points at that folder.

## What this workflow does not do

- It does **not** produce a Play Store / release-signed APK.
- For a release APK you need a keystore and GitHub secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
