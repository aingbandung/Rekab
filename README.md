# ReKab by firdaus

**Rekam Kamera Bawaan** — turns the car's own cameras into a dashcam.

ReKab runs **on the head unit** of the Omoda E5 EV (Android-based, Qualcomm 8155), not on the phone.
Android Auto cannot pass the car's cameras to a phone, so the app has to live where the cameras are.

## What it does

- Lists every camera the head unit lets apps open (including hidden camera IDs 0–31), with a **Preview** button so you can see which is which.
- Records the cameras you tick, at the same time, as looping MP4 clips (1–10 min each). Oldest clips are deleted when the loop limit is reached; **Lock** keeps a clip forever.
- Saves to the head unit's internal storage or a USB drive.
- **Save to phone:** connect the head unit to your phone's Wi-Fi hotspot and open the address shown in the app (e.g. `http://192.168.43.x:8080`) in the phone's browser. You can play, download, lock and delete clips there.
- Optional microphone audio, and optional auto-start when the car turns on.
- Keeps retrying when the system takes a camera (for example the 360° view while reversing).

## Download

The latest APK is in [Releases](../../releases) and in [`release/ReKab-by-firdaus.apk`](release/). Every push to `main` builds a new one with GitHub Actions.

## Install on the Omoda E5

1. On the head unit: **Local Setting → System Setting**, tap the upper-left-to-center area 10 times to open the hidden menu.
2. Open **ADB** (the unit may ask for a password derived from the serial/product ID) and set it to **Open**.
3. Connect a Windows PC to the head unit's USB-A port and run:
   ```
   adb devices
   adb push ReKab-by-firdaus.apk /data/local/tmp/
   adb shell pm install -g /data/local/tmp/ReKab-by-firdaus.apk
   adb shell am start -n com.firdaus.rekab/.MainActivity
   ```
   `-g` grants camera and microphone permission at install time.
4. If the camera list is empty, run this and keep the output — it shows how the unit exposes its cameras:
   ```
   adb shell dumpsys media.camera > cameras.txt
   ```

## Important

Whether the car's cameras can be opened by an installed app depends on the head unit firmware. If the cameras are only wired into the 360° system and not exposed through Android's camera service, the list will be empty and recording is not possible without firmware changes. The app shows this clearly in its log.

Using the hidden menu and ADB is at your own risk.

## Build

Open in Android Studio, or `./gradlew assembleRelease`. The signing key in `keystore/` is for this personal project only.
