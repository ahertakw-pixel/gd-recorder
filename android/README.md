# GD Recorder for Android

Native Android screen recorder and run library. It uses `MediaProjection` and `MediaRecorder` to capture MP4 directly, then scans the saved recording with bundled ML Kit text recognition to find percentages and split attempts. OCR runs after recording stops, so it does not compete with Geometry Dash during gameplay.

## Use

1. Install `dist/GD Recorder Android v1.apk` and open GD Recorder.
2. Enter the level name and starting percentage; select **Startpos** for a start-position run.
3. Tap **Start screen capture** and approve Android's screen-capture prompt. Switch to Geometry Dash and play. Stop from the persistent recorder notification or return to GD Recorder and tap **Stop + split runs**.
4. Wait for offline percentage detection and attempt splitting. MP4 attempt clips are saved to `Movies/GD Recorder`; the run list shows each attempt, best percentage, and OCR marker count.
5. Type a percentage such as `23` to find runs that reached it, or a range such as `5-23` to export and play that portion. Ranges only match Startpos attempts. If multiple attempts match, choose the numbered run.

The app requires Android 10 or newer. Android displays a consent prompt for each screen-capture session; recent Android versions also require the media-projection foreground service declaration. See the [Android MediaProjection guide](https://developer.android.com/media/grow/media-projection) and [foreground service requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required).

This first Android build records video without audio. OCR samples the upper center portion of the saved video about twice per second. It cannot split a run if the HUD percentage is unreadable; the original capture is retained as one clip in that case. Segment export uses the nearest encoded keyframe, so cut points can include a small amount of adjacent footage.

## Build and checks

Requires JDK 17 and Android SDK Platform 35. From this folder:

```powershell
./gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Unit tests cover OCR text parsing, input bounds, reset-based attempt splitting, and percentage-time interpolation. A device or emulator is required to exercise actual screen capture and Geometry Dash OCR.
