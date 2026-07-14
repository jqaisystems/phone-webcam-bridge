# Android Build Setup

## Requirements

- Android Studio
- Android SDK Platform 35
- Android SDK Build Tools
- Android Platform Tools for `adb`
- USB debugging enabled on the Android phone for USB mode

## Build and run

1. Open Android Studio.
2. Open the folder `apps/android`.
3. Let Gradle sync.
4. Connect the Android phone.
5. Run the `app` configuration.

The first app launch asks for camera permission. After permission is granted, the app starts:

- CameraX preview
- local HTTP server on port `47888`
- UDP discovery beacon on port `47887`

## Endpoints on the phone

```text
GET  /status
POST /pair
GET  /stream.mjpg
GET  /snapshot.jpg
POST /control
```

`/stream.mjpg`, `/snapshot.jpg`, and `/control` require the token returned by `/pair`.

## Notes

- Default profile is Smooth: `1280x720`, 30 fps, JPEG quality 68.
- HD profile is `1280x720`, 15 fps, JPEG quality 70.
- Low profile is `640x360`, 15 fps.
- The analysis resolution is capped with a `ResolutionSelector` (some front cameras ignore `setTargetResolution` and hand back full-sensor frames), and the sensor fps is driven via Camera2Interop `CONTROL_AE_TARGET_FPS_RANGE`.
- Phone audio is intentionally out of scope for v1.
