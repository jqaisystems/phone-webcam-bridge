# Local API

## PC bridge

Base URL:

```text
http://127.0.0.1:47889
```

Routes:

- `GET /`: control UI.
- `GET /obs.html`: clean OBS Browser Source view.
- `GET /api/state`: bridge, phone, OBS, and discovery state.
- `GET /api/discovered`: phones found by UDP beacon.
- `POST /api/connect`: body `{ "mode": "wifi|usb|manual", "host": "192.168.1.42:47888", "pin": "123456" }`.
- `POST /api/disconnect`: clears the active phone.
- `POST /api/control`: forwards camera settings to the phone.
- `POST /api/usb/forward`: runs `adb forward tcp:47888 tcp:47888` if ADB is available.
- `POST /api/obs/start`: creates/updates the OBS scene and starts OBS Virtual Camera.
- `GET /proxy/stream.mjpg`: proxied phone MJPEG stream.
- `GET /proxy/snapshot.jpg`: proxied phone snapshot.
- `GET /proxy/audio.wav`: proxied phone microphone (endless WAV, 48 kHz mono 16-bit; mic must be on).

## Android phone

Base URL:

```text
http://PHONE_IP:47888
```

Pair:

```http
POST /pair
Content-Type: application/json

{ "pin": "123456" }
```

Control:

```http
POST /control
Authorization: Bearer TOKEN
Content-Type: application/json

{
  "profileId": "smooth",
  "camera": "front",
  "mirror": true,
  "torch": false,
  "mic": false,
  "zoomRatio": 1.0,
  "exposureIndex": 0
}
```

Profiles: `smooth` (720p/30, default), `fhd` (1080p/30), `hd` (720p/15), `low` (360p/15).

Stream:

```text
GET /stream.mjpg?token=TOKEN
```

Snapshot:

```text
GET /snapshot.jpg?token=TOKEN
```

Microphone (endless WAV, 48 kHz mono 16-bit PCM; requires `"mic": true` first):

```text
GET /audio.wav?token=TOKEN
```

`GET /status` also reports `actualFps`, `cameraState` (zoom/exposure ranges), `audio`
(format), and `tokenValid` when a token is presented.
