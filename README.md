# Phone Webcam Bridge

Private Android-to-Windows webcam bridge for meetings. The phone streams camera frames over Wi-Fi or USB, the PC bridge normalizes the feed, and OBS Virtual Camera exposes it to Zoom, Google Meet, Teams, and similar apps.

<picture><source media="(prefers-reduced-motion: reduce)" srcset="docs/demo-poster.png"><img src="docs/demo.gif" alt="Phone Webcam Bridge demo"></picture>

[Static preview](docs/demo-poster.png) · [Requirements](#requirements) · [Quick start](#quick-start) · [Documentation](#documentation) · [Support](#support)

<a id="requirements-and-quick-start"></a>

## Requirements

Use a Windows PC with Node.js and npm, OBS Studio with Virtual Camera, and
an Android phone. Install Android Studio and its SDK/platform tools to build
the app; USB mode also needs adb and phone USB debugging. See
[Android build setup](docs/ANDROID_BUILD.md) and [the user guide](docs/USER_GUIDE.md).

## Quick start

Run these commands from the repository root:

```powershell
.\scripts\check-environment.ps1
.\scripts\allow-firewall.ps1   # once per PC: allows phone discovery on Public networks
.\scripts\run-pc-bridge.ps1
```

Open the bridge:

```text
http://127.0.0.1:47889
```

## Documentation

- [User guide (non-technical)](docs/USER_GUIDE.md)
- [Phone microphone setup](docs/PHONE_MIC_SETUP.md)
- [Meeting workflow](docs/MEETING_WORKFLOW.md)
- [Android build setup](docs/ANDROID_BUILD.md)
- [Local API](docs/API.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)

## Support

Read [troubleshooting](docs/TROUBLESHOOTING.md), then open a [GitHub issue](https://github.com/jqaisystems/phone-webcam-bridge/issues/new/choose). Include Android/Windows versions, USB or Wi-Fi mode and a redacted error; leave pairing PINs and device addresses out.

## What is included

- `apps/android`: Android Kotlin + CameraX app with MJPEG stream server.
- `apps/pc-bridge`: Node + TypeScript local bridge and control UI.
- `scripts`: Windows helper scripts for checks, USB forwarding, and running the bridge.
- `docs`: setup, meeting workflow, API, and troubleshooting notes.

## Working from a new location

- For USB mode, enable phone USB debugging and authorize the PC, then click `Connect USB`. The bridge sets up adb forwarding when adb is available.
- Wi-Fi: keep the phone and PC on a network that allows device-to-device traffic. Saved pairing, UDP discovery and subnet scanning help reconnect; guest isolation and firewall rules can prevent discovery.
- Full checklist: [Troubleshooting](docs/TROUBLESHOOTING.md#new-location-checklist).

## Android build

Install Android Studio and the SDK components listed in [Android build setup](docs/ANDROID_BUILD.md). Open `apps/android`, let Gradle sync, then build and run the app on your phone.

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

## Reuse and licensing

This repository does not currently include an open-source licence. For reuse enquiries, [contact the maintainer](https://www.ai.joaoqueiros.com/contact). Third-party dependencies retain their own licences.
