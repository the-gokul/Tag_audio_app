# Tag Mobile — Android

UI from `preview_home.html`. BLE via Nordic Maven libraries:

- `no.nordicsemi.android:ble-ktx`
- `no.nordicsemi.android.support.v18:scanner`

## Behavior

1. **Scan** — lists **Tag** devices only (`Tag` / `Tag_*` or `TAG_STREAM`).
2. **Connect** — succeeds only if TAG_STREAM GATT is present.
3. **Start / Stop / Save** — START+time sync, STOP, Excel/CSV with timestamps.

4. **Upload** — sessions sync to https://collar.justkodez.com in resumable 8 MB chunks;
   long recordings continue where they stopped. Progress, speed and time left show in the
   notification and on the Recordings (History) screen.

Build APK with GitHub Actions (see `BUILD_APK_GITHUB.md`).
The debug APK is uploaded as a GitHub Actions artifact after each push to `main`.

## Upload server settings

The build already points at the live server (defaults in `app/build.gradle.kts`), so no
setup is needed. To use a different server, add both lines to `local.properties`, or set
them as environment variables / GitHub Actions secrets:

```
TAG_SERVER_URL=https://collar.justkodez.com
TAG_ENROLL_KEY=<enrollment key>
```

To check which server a build uses, open the in-app log and look for
`CLOUD_BACKEND TagServerCloudBackend https://…`.
