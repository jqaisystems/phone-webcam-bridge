# Using the phone microphone

By default this project sends **video only**. Your meeting audio keeps coming from the
PC microphone, which is the simplest and most reliable setup.

If you would rather use the **phone's microphone** as well (for example the phone is
closer to you than the laptop), follow this guide. It takes one extra free program and a
few minutes of setup.

## Before you start: two things to know

1. **You must install VB-CABLE.** The OBS Virtual Camera carries video only, so the phone
   audio needs a small virtual audio device (VB-CABLE) to reach your meeting app. There is
   no way around this on Windows; a meeting app can only pick a real audio device as its
   microphone.

2. **Use headphones.** With the phone mic on and the meeting playing through your PC
   speakers, the phone will hear the speakers and the other people get an echo of
   themselves. Headphones break that loop. This is true of any "phone as microphone"
   setup, not a bug in this project.

If you skip VB-CABLE, turning the phone mic on just plays the phone audio out your PC
speakers, which the phone then re-records: that is the echo people hear. So install
VB-CABLE first, or leave the phone mic off and use the PC mic.

---

## Step by step

### 1. Install VB-CABLE (one time, needs admin)

1. Download VB-CABLE from https://vb-audio.com/Cable/ (the free "VB-CABLE Driver").
2. Unzip it, right-click `VBCABLE_Setup_x64.exe`, and choose **Run as administrator**.
3. Click **Install Driver**.
4. **Reboot the PC.** VB-CABLE does not work correctly until you restart.

After rebooting you will have two new audio devices: **CABLE Input** (a playback device)
and **CABLE Output** (a recording device). They carry no sound of their own; they are just
a private pipe from OBS to your meeting app.

### 2. Point OBS at CABLE Input (one time)

1. Start the bridge as usual (it launches OBS for you).
2. In OBS: **Settings > Audio > Advanced**.
3. Set **Monitoring Device** to **CABLE Input (VB-Audio Virtual Cable)**.
4. Click **OK**.

The bridge already tells OBS to monitor the phone audio, so once the monitoring device is
CABLE Input, the phone mic flows into that virtual cable instead of your speakers.

### 3. Turn the phone mic on

- On the phone: tap **Mic off** so it reads **Mic on** (grant the microphone permission
  the first time), **or**
- On the dashboard at `http://127.0.0.1:47889`: tick **Phone mic**.

### 4. Choose CABLE Output as your microphone in the meeting

- **Google Meet:** Settings (gear) > Audio > Microphone > **CABLE Output**.
- **Zoom:** Settings > Audio > Microphone > **CABLE Output**.
- **Teams:** Settings > Devices > Microphone > **CABLE Output**.

### 5. Put on headphones and test

Join a test meeting (Meet and Zoom both let you preview your mic). Speak into the phone and
watch the input meter move. Use headphones so the meeting audio does not leak back into the
phone mic.

---

## Turning it back off

- Untick **Phone mic** (or tap **Mic on** so it reads **Mic off**) on the phone.
- In your meeting app, switch the microphone back to your normal PC mic.

You can leave VB-CABLE installed; it does nothing until OBS monitors audio to it.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Echo / feedback | Use headphones. If you have no headphones, keep the phone mic off and use the PC mic. |
| Still echoing with headphones | OBS Monitoring Device is still your speakers. Set it to **CABLE Input** (step 2). |
| "CABLE Output" is not in the meeting's mic list | VB-CABLE is not installed or the PC was not rebooted after install. |
| Meeting mic meter does not move | Confirm the phone mic is on (dashboard shows it), and the meeting mic is **CABLE Output**, not CABLE Input. |
| Robotic or choppy audio | Weak USB/Wi-Fi link. Prefer the USB cable; the audio reconnects on its own after a drop. |
| Meeting picks up both mics | Only select **CABLE Output**; do not leave the laptop mic selected as a second source. |

---

## How it works (for the curious)

```
phone mic  ->  /audio.wav (48 kHz mono PCM over HTTP)  ->  PC bridge /proxy/audio.wav
           ->  OBS browser source (plays the audio, reroute_audio on)
           ->  OBS monitoring  ->  CABLE Input  ->  CABLE Output  ->  meeting mic
```

Video and audio travel independently. There is no lip-sync muxing, which is fine for
meetings and keeps latency low (around 0.2 seconds of audio delay).
