# Drishti AI

Drishti AI is an Android assistive app for blind and low-vision users. It uses the phone camera to detect objects and potholes, then gives spoken and vibration alerts. Detection and enrolled-person matching are designed to run on the device. An optional, user-triggered scene description sends a single image to Gemini.

## Features

- **Objects mode:** Detects 80 COCO object classes with the bundled YOLO26n LiteRT model.
- **Road mode:** Detects potholes with a separate LiteRT model. Objects and potholes are not detected at the same time.
- **Spoken and vibration alerts:** Alerts include approximate left, center, or right position. Speech is available in English, Hindi, and Marathi; Hindi and Marathi need the corresponding offline voice installed in Android Text-to-Speech settings.
- **Known people:** Optionally enroll people for on-device face matching. The app stores names and encrypted face embeddings in app-private storage, not face photographs.
- **Optional scene description:** Triple-tap the camera preview to request a short Gemini description of one captured image. The app does not continuously stream video to Gemini.
- **Accessibility and device settings:** TalkBack support, adjustable haptic settings, auto torch, and pocket touch lock are included.

## Requirements

- Android Studio with Android SDK Platform 36 (including the configured 36.1 extension level).
- Android device running Android 10 (API 29) or later, with a camera.
- The Gradle wrapper downloads the configured Gradle distribution when needed.

## Build

Open this project in Android Studio and allow Gradle sync to finish, then run the `app` configuration on a device.

To build a debug APK from the project root on Windows:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Optional Gemini setup

Scene descriptions are disabled unless a Gemini API key is supplied in the ignored, local `local.properties` file:

```properties
geminiApiKey=YOUR_KEY
```

The debug build embeds this value in the APK, where it can be extracted. Use only a personal development key, and do not share an APK built with that key. The release build currently has no Gemini key configured; a distributed build should call Gemini through a backend or another managed setup that does not expose a secret in the app.

## Tests

Run JVM unit tests with:

```powershell
.\gradlew.bat testDebugUnitTest
```

Instrumentation tests require a connected Android device or emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Use `./gradlew` instead of `.\gradlew.bat` on macOS or Linux.

## Limitations and privacy

- Object and pothole detection process camera frames on-device. A single image is sent over the internet only when the user requests a Gemini scene description.
- Known-person matching recognizes only people enrolled by the user. Results can be unreliable for small, turned-away, occluded, or poorly lit faces.
- Pothole detection is a separate mode. The object model also does not cover many navigation hazards, such as stairs, curbs, glass doors, or overhead obstacles.
- Position and apparent-size cues are estimates, not physical distance measurements. Device speed, lighting, heat, and camera placement can affect detection.
- Treat alerts as assistive cues; the app cannot determine or guarantee that a route is safe.

For implementation details, architecture notes, model limitations, and additional privacy information, see [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md). Pothole model training and export scripts are in [`tools/`](tools/), with instructions in [`tools/POTHOLE_MODEL.md`](tools/POTHOLE_MODEL.md).
