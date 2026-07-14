# Troubleshooting

## New location checklist

Working from a new place (different Wi-Fi, coworking, client office):

1. Start the bridge: `.\scripts\run-pc-bridge.ps1`. Nothing works without it.
2. If you have the cable: plug it in and click `Connect USB`. USB mode ignores the network entirely, so it works everywhere. This is the recommended mode at unknown venues.
3. For Wi-Fi: run `.\scripts\allow-firewall.ps1` once per PC. New networks are usually classified as Public and Windows Firewall silently blocks phone discovery there.
4. Phone and PC must share a network. If the PC is on Ethernet and the phone on the venue Wi-Fi, they may be isolated from each other (common in offices and guest networks). In that case use USB, or connect the PC to the same Wi-Fi.
5. If you paired once before, the bridge auto-reconnects: it remembers the phone and its PIN, tries USB first, then searches the network. Just wait a few seconds after opening the bridge.

## Phone is not discovered

- Confirm phone and PC are on the same Wi-Fi.
- Run `.\scripts\allow-firewall.ps1` once (allows discovery on Public networks).
- The bridge also actively scans the local network every ~10 seconds when no phone is found, so wait about 15 seconds.
- Disable VPNs that isolate local network traffic.
- Enter the phone URL manually from the Android app, for example `192.168.1.42:47888`.
- Guest / hotel Wi-Fi often has client isolation: devices cannot see each other at all. Use USB mode there.

## PIN fails

- Use the current PIN shown in the Android app.
- The PIN is stable: it is generated once and kept across app restarts, so the bridge can reconnect automatically. To force a new PIN, clear the app's storage in Android settings.

## USB mode fails

- Enable Developer Options on Android.
- Enable USB debugging.
- Accept the authorization prompt on the phone (tick "Always allow from this computer").
- `Connect USB` in the bridge now sets up the adb port forward automatically and reports the exact problem (no device, unauthorized, charge-only cable).
- Manual fallback:

  ```powershell
  adb devices
  adb forward tcp:47888 tcp:47888
  ```

If `adb` is missing, install Android Studio or Android Platform Tools.

## OBS button fails

- Open OBS first.
- In OBS, go to `Tools > WebSocket Server Settings`.
- Enable the websocket server on port `4455`.
- If authentication is enabled, enter the OBS websocket password in the bridge UI.
- Manual fallback: add `http://127.0.0.1:47889/obs.html` as an OBS Browser Source.

## Meeting app does not show the camera

- Start OBS Virtual Camera.
- Close and reopen the meeting app.
- Select `OBS Virtual Camera` from the meeting app camera menu.
- On Windows, only one app may hold a camera source cleanly at a time.

## Latency or low quality

- Use USB mode when possible.
- Use the `Low 360p / 15 fps` profile on weak Wi-Fi.
- Keep the phone plugged into power for long meetings.
- Use the PC microphone; phone audio is not implemented in v1.
