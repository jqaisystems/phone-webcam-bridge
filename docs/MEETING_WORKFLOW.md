# Meeting Workflow

## Daily use (after the first pairing)

The bridge remembers the phone, its PIN, and the OBS password, so the normal flow has no typing at all:

1. Plug the phone in with the USB cable (or put it on the same wifi as the PC) and open the **Phone Webcam** app. The screen stays awake by itself.
2. Start the PC bridge if it is not already running:

   ```powershell
   .\scripts\run-pc-bridge.ps1
   ```

   The bridge reconnects to the phone automatically within about 10 seconds (USB first, then wifi).
3. Open OBS, then in the bridge UI (`http://127.0.0.1:47889`) click `Start OBS Camera`. The password field can stay empty; it is remembered from the first run.
4. In Zoom, Google Meet, Teams, or another meeting app, select `OBS Virtual Camera`. If the app was already open, refresh the Meet tab or reopen Zoom so the camera list updates.
5. Use the PC microphone. The phone sends video only.

During the call keep three things true: the phone app in the foreground with the cable plugged, OBS open (minimized is fine), and the bridge window running. If video ever freezes, wait about 10 seconds; the bridge and the OBS page recover on their own.

## First pairing on a new PC or after clearing data

1. Start the bridge and open `http://127.0.0.1:47889`.
2. USB: plug the cable, accept the "Allow USB debugging?" prompt on the phone, type the PIN shown in the phone app, click `Connect USB` (the bridge sets up adb forwarding itself).
3. Wi-Fi: both devices on the same network, run `.\scripts\allow-firewall.ps1` once per PC, then select the discovered phone (or click `Scan network`), type the PIN, click `Connect Wi-Fi`.

The PIN is stable across app restarts. It only changes if the app's storage is cleared on the phone.

## Manual OBS fallback

If the OBS button fails:

1. Open OBS and check `Tools > WebSocket Server Settings` is enabled (port 4455).
2. Or configure by hand: create a scene named `Phone Webcam`, add a Browser Source with URL:

   ```text
   http://127.0.0.1:47889/obs.html
   ```

3. Set the source width and height to match the OBS canvas (Settings > Video > Base Resolution), stretch it to fill the scene, then click `Start Virtual Camera`.
