# AIROX Connect

**AIROX Connect** is the Android app for [AIRUX](https://github.com/Absurd28/AIROX_OS), the Debian-based
operating system with **AIRA** (*Adaptive Intelligent Research Assistant*). Pair your phone or
tablet with your AIRUX PC by QR code, and on the same network you can:

| | |
| --- | --- |
| 🎙️ **Talk to AIRA** | Type or speak. Your voice is transcribed on the PC (whisper.cpp) and AIRA answers on the phone in her own voice. Her verified / unverified / "not sure" tags come with every answer. |
| 🏠 **See what is going on** | CPU, memory and GPU meters, running apps, what AIRA is doing, recent actions, Phagos antivirus status. |
| 📊 **Task Manager** | Processes (apps, background, system), live performance graphs (CPU, memory, disks, network, GPU), startup apps, services and users. End a program, change a priority, start or stop a service. |
| 📁 **Files both ways** | Browse your PC's home folder and download anything; send photos, videos and files to the PC (or use Android's share sheet). Resumable, and checked with SHA-256. |
| 📋 **Clipboard and links** | Copy between phone and PC; open a link from the phone in NEOX on the PC. |
| 🖱️ **Touchpad and media** | Mouse, keyboard, play/pause, next, volume. |
| 🖥️ **Second screen** | Your phone or tablet becomes another monitor of the PC (extend the desktop onto it) with touch, or mirrors a screen. The delay from the PC to the screen is shown while it runs. |
| 🔌 **Power** | Lock, sleep, restart, shut down, and wake the PC with Wake-on-LAN. |
| 🔔 **Alerts and approvals** | AIRA's reminders, finished jobs and Phagos warnings as notifications; approve or deny AIRA's risky actions from the phone; "ring my phone". |

## Safe by design

- **Your network only.** The PC accepts phones only from local addresses, and only after you turn
  on *Allow phone connections* on the PC.
- **Paired, not guessed.** The QR code is valid for two minutes, works once, and you click *Allow*
  on the PC.
- **Encrypted to your PC.** TLS 1.3 to your PC's own certificate, whose fingerprint comes in the QR
  code: no other computer can pretend to be it. The phone's token is kept encrypted with the
  Android Keystore.
- **AIRA's rules apply.** Every action from the phone goes through AIRA's permission broker on the
  PC. Ending programs, sending files off the PC, power actions and the like ask you first; deleting,
  root actions and installs also need your phone's screen lock. Everything is logged on the PC.
- **No cloud.** Nothing goes through a server; there is no account and no tracking.

## Install

Android 10 or newer.

1. On the AIRUX PC open **AIROX Connect** from the Start menu and turn on **Allow phone connections**.
2. Click **Share the app** and scan the code with the phone's camera, or copy
   `/usr/share/airox-connect/airox-connect.apk` from the PC. Open the APK and allow installing from
   that source.
3. In the app tap **Scan the pairing code**; on the PC click **Show pairing code**, then **Allow**.

The second screen and the touchpad need a one-time OK on the PC: KDE asks ("Screen Sharing": choose
**New Virtual Output**; "Remote control": **Share**).

## Build

Requires the Android SDK (platform 36) and JDK 17 or newer (Android Studio's is fine).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebugAndroidTest
```

## Tests

- `app/src/androidTest/.../UnitTests.kt`: pairing link parsing, certificate pin, discovery replies,
  Wake-on-LAN packet, WAV recording format, H.264 key frames, transcript merging. Run on a device or
  emulator: `adb shell am instrument -w -e notClass org.airux.connect.E2E org.airux.connect.test/androidx.test.runner.AndroidJUnitRunner`.
- `app/src/androidTest/.../E2E.kt`: the whole app against a real AIRUX PC. AIRUX's
  `tests/connect_check.py` runs it with AIRUX in QEMU and plays the person at the PC (allows the
  pairing, answers KDE's windows) while checking every result on the PC.

## How it works

The app talks to `airox-connectd` on the PC (part of AIRUX): HTTPS for requests, one WebSocket for
AIRA's conversation, confirmations, alerts and input, and another for the second screen's H.264
video, decoded by the phone's hardware decoder in low-latency mode. The protocol is in
[docs/PROTOCOL.md](docs/PROTOCOL.md), so an iOS app can follow.

Source layout (`app/src/main/java/org/airux/connect/`):

```text
data/       pairing link, paired PC and its Keystore-encrypted token
net/        certificate pinning, the session (REST, events WebSocket, reconnect), discovery, Wake-on-LAN
media/      push-to-talk recording (16 kHz WAV), AIRA's voice on the phone
transfer/   resumable uploads and downloads with SHA-256 checks
stream/     second screen: WebSocket -> MediaCodec, clock sync and delay
service/    foreground service, notifications, approvals, "ring my phone"
ui/         Compose screens: pairing, Home, AIRA, Tasks, Files, More; second screen; share sheet
```

## Roadmap

- [x] Android: AIRA, Task Manager, files, clipboard, touchpad, second screen, power, alerts (same network)
- [ ] Reaching the PC from other networks (a Tor onion service: no account, no port forwarding)
- [ ] iOS

## Licence

MIT. Libraries: AndroidX, Jetpack Compose, Material 3, CameraX, Kotlin and kotlinx.coroutines,
OkHttp and Okio, ZXing core, all Apache-2.0.
