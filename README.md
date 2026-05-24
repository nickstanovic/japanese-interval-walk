# Japanese Interval Walk

A personal Android interval-walking app for a Pixel 10 Pro.

The timer alternates forever between:

- 3 minutes slow walking: one long vibration cue
- 3 minutes fast walking: two short vibration cues

The app includes pause/resume so a shoe tie or sit-down rest does not consume interval time. It also plays upbeat bundled English voice cues for start, slow, fast, pause, and stop. The active timer runs as a foreground service with a notification, so the cadence keeps going when the screen is off.

The app is written in Kotlin with Jetpack Compose and Material 3.

Voice clips are generated locally with Kokoro-82M's English `af_heart` voice. Kokoro-82M is listed by its upstream model card as Apache-2.0 licensed, and this app bundles only the generated MP3 cues for offline playback; it does not bundle Kokoro model weights or call any online TTS API at runtime. Stop summaries use pre-generated Kokoro clips selected by completed interval count, so the app can announce what was accomplished while staying fully offline.

Voice asset attribution: generated with [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M), `af_heart` voice, Apache-2.0 model license.

## Build

Open this folder in Android Studio, let it install any missing Android SDK pieces, then run the `app` configuration on the configured Android virtual device.

If Android Studio, Java, and the Android SDK are already available from the command line, you can also run:

```bat
gradlew.bat assembleDebug
```

The debug APK will appear under `app\build\outputs\apk\debug\`.

Example local Windows setup:

- `JAVA_HOME` can point at `%USERPROFILE%\.jdks\openjdk-22.0.1`
- Android SDK platform 36 can be installed under `%LOCALAPPDATA%\Android\Sdk`

## Emulator Testing

An Android 36 emulator named `Pixel_10_Pro_API_36` is configured for testing. The SDK does not currently expose a built-in Pixel 10 Pro hardware profile, so this AVD uses the closest available Google profile while keeping the Pixel 10 Pro name for the test target.

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug
```

The emulator screenshots captured during testing are saved as `emulator-compose-home.png`, `emulator-compose-running.png`, and `emulator-compose-paused.png`.
