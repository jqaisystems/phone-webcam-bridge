# Phone Webcam - User Guide

Turn your Android phone into a high-quality webcam for Google Meet, Zoom, or any app,
over a USB cable (most reliable) or Wi-Fi.

---

## What you need

- An Android phone (Android 8 or newer)
- A Windows PC
- A USB cable that can carry data (the one that charges + connects to a PC)
- Free software on the PC: **Node.js**, **OBS Studio**, and optionally **VB-CABLE** (for phone-mic audio)

---

## One-time setup

### 1. Install the app on your phone

Install `app-release.apk` (from `apps/android/app/build/outputs/apk/release/`). Copy it to
the phone and tap it, or install over USB. On Xiaomi/MIUI phones you must first allow it:

- Settings > Additional settings > Developer options > turn ON **Install via USB** and
  **USB debugging**. (You may need to sign in to a Mi account for "Install via USB".)
- When installing, tap **Install** on the phone if a confirmation appears.

### 2. Set up the PC

Double-click `scripts/install.ps1` (or run it in PowerShell). It checks Node.js + OBS,
installs dependencies, and creates a **"Phone Webcam Bridge"** shortcut on your desktop.

Missing pieces it will point you to:
- **Node.js**: https://nodejs.org/ (LTS version)
- **OBS Studio**: https://obsproject.com/ (the bridge launches it for you)
- **VB-CABLE** (optional, only for phone-mic audio): https://vb-audio.com/Cable/ (needs admin)

### 3. Enable OBS WebSocket (one time)

Open OBS once, go to **Tools > WebSocket Server Settings**, tick **Enable WebSocket Server**,
and click OK. (The bridge talks to OBS through this.)

---

## Everyday use

1. Plug the phone into the PC with the USB cable.
2. Open the **Phone Webcam** app on the phone.
3. Double-click **Phone Webcam Bridge** on your desktop (if it is not already running).
   It opens a small window and automatically starts OBS in the background.
4. In **Google Meet / Zoom**, open camera settings and choose **OBS Virtual Camera**.

That's it. Wait about 15 seconds after starting the bridge for OBS to warm up. The phone
screen can be turned off; streaming keeps running.

Open **http://127.0.0.1:47889** in a browser to see the control dashboard (preview,
camera switch, zoom, exposure, mic toggle, live fps).

---

## Using the phone microphone (optional)

Meeting audio comes from the PC mic by default. To use the phone mic instead, you need the
free VB-CABLE virtual audio device and a few OBS settings. Full walkthrough:
[Phone microphone setup](PHONE_MIC_SETUP.md).

**Use headphones** with the phone mic, or people will hear an echo of themselves.

---

## Wi-Fi instead of USB

Wi-Fi only works when the phone and PC are on the **same Wi-Fi network**.

1. Connect both devices to the same Wi-Fi.
2. Run `scripts/allow-firewall.ps1` once (lets the PC receive the phone's beacon).
3. On the dashboard, click **Scan network**, then **Connect Wi-Fi**.

If the phone doesn't appear, they are probably on different networks (common with mesh
Wi-Fi or guest networks). Use the USB cable instead.

---

## Keeping it alive on Xiaomi / MIUI phones

MIUI aggressively closes background apps. For long meetings:

- Settings > Apps > Phone Webcam > **Battery saver** > **No restrictions**.
- Settings > Apps > Phone Webcam > **Autostart** > ON.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Black screen in Meet/Zoom | OBS wasn't ready yet. Wait ~15s, or click "Start OBS Camera" on the dashboard. |
| Dashboard says "fetch failed" | Make sure the **Phone Webcam** app (not another camera app) is open on the phone. |
| Dashboard says "Phone PIN changed" | The app was reinstalled. Type the PIN shown on the phone and press Connect. |
| Video is choppy | Try the "HD 720p / 30 fps" profile; the back camera slows down in dim light. |
| No "OBS Virtual Camera" in Meet/Zoom | Restart the meeting tab/app so it re-reads the camera list. |
| Phone mic not heard | Confirm VB-CABLE is installed, OBS monitoring device = CABLE Input, and Meet mic = CABLE Output. |
| Install blocked on phone | Enable "Install via USB" in Developer options, and tap Install on the phone. |
