# Phone Webcam Bridge

Private Android-to-Windows webcam bridge for meetings. The phone streams camera frames over Wi-Fi or USB, the PC bridge normalizes the feed, and OBS Virtual Camera exposes it to Zoom, Google Meet, Teams, and similar apps.

## What is included

- `apps/android`: Android Kotlin + CameraX app with MJPEG stream server.
- `apps/pc-bridge`: Node + TypeScript local bridge and control UI.
- `scripts`: Windows helper scripts for checks, USB forwarding, and running the bridge.
- `docs`: setup, meeting workflow, API, and troubleshooting notes.

## Quick start on this PC

```powershell
.\scripts\check-environment.ps1
.\scripts\allow-firewall.ps1   # once per PC: allows phone discovery on Public networks
.\scripts\run-pc-bridge.ps1
```

Open the bridge:

```text
http://127.0.0.1:47889
```

## Working from a new location

- USB cable is the always-works mode: plug in, click `Connect USB`. The bridge sets up adb forwarding automatically.
- Wi-Fi: after pairing once, the bridge remembers the phone and PIN and reconnects automatically on any network (UDP discovery plus an active subnet scan when broadcasts are blocked).
- Full checklist: [Troubleshooting](docs/TROUBLESHOOTING.md#new-location-checklist).

## Android build

This machine currently needs Android tooling installed before the phone app can be built. Install Android Studio, open `apps/android`, let Gradle sync, then run the app on your Android phone.

The phone app shows:

- the 6-digit PIN for pairing;
- the local phone URL, usually `http://PHONE_IP:47888`;
- controls for profile, camera, mirror, and torch.

## OBS / meetings

OBS Studio is already the intended virtual camera layer. In the bridge UI, click `Start OBS Camera`. If OBS websocket is not available, add this URL manually as an OBS Browser Source:

```text
http://127.0.0.1:47889/obs.html
```

Then start OBS Virtual Camera and choose `OBS Virtual Camera` in your meeting app.

## Optional: phone microphone

Video is sent on its own, so meeting audio comes from the PC mic by default. To use the
phone microphone instead, install VB-CABLE and route the audio through OBS: full steps in
[Phone microphone setup](docs/PHONE_MIC_SETUP.md). Use headphones to avoid echo.

## References

- [User guide (non-technical)](docs/USER_GUIDE.md)
- [Phone microphone setup](docs/PHONE_MIC_SETUP.md)
- [Meeting workflow](docs/MEETING_WORKFLOW.md)
- [Android build setup](docs/ANDROID_BUILD.md)
- [Local API](docs/API.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
