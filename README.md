# GD Recorder

Windows and Android Geometry Dash attempt recorder and run library. It records MP4 with native platform capture APIs, then reads the visible progress HUD locally to track and split attempts. It does not use FFmpeg.

Windows releases are named sequentially (`GD Recorder v1.exe`, `v2.exe`, etc.). Android APK versions use their own sequence. Build instructions for each platform are below; generated binaries are not committed to this source repository.

## Use

1. Enter the level name and starting percentage. For a start-position attempt, check **Startpos run** and enter its start percentage.
2. No HUD crop setup is needed. The recorder searches the upper HUD area for the percentage when recording starts and locks onto it. If automatic detection cannot find it, stop and use **Manual HUD fallback** once.
3. Press **Arm capture**, switch to Geometry Dash, then press **F10** to start the session. Press **F10** again to stop. New attempts are detected when the percentage returns near the starting percentage after progress has advanced. Each attempt is saved as its own MP4 in `Videos/GD Recorder`.
4. The live detected percentage is shown in the app. **F7** opens the optional manual marker field. Double-click a grouped run to choose an attempt, inspect every detected percentage and timestamp, and play that attempt.
5. Type `23` to find attempts containing 23%, or `5-23` to play that segment. The dash is required, and ranges only match Startpos attempts. If several attempts match, enter their displayed number to open the one you want.

The recorder searches the upper HUD area once when a session starts, then OCR samples only the detected percentage crop five times per second to keep processing light. The percent list is limited by what the screen shows and what OCR can read between samples. Percentage ranges play from the saved MP4 in your browser; they do not create a second trimmed file. Run metadata and crop settings are stored in `%APPDATA%/GD Recorder`.

## Build

Requires Python 3.12+, Tkinter, and PyInstaller:

```powershell
python -m pip install pyinstaller tesserocr==2.12.0
python -m PyInstaller --noconfirm --clean --onefile --windowed --collect-all tesserocr --add-binary "wcap-x64.exe;." --add-data "tessdata/eng.traineddata;tessdata" --add-data "WCAP-LICENSE.txt;." --add-data "TESSDATA-LICENSE.txt;." --add-data "TESSEROCR-LICENSE.txt;." --name "GD Recorder v4" gd_recorder.py
```

Build output: `dist/GD Recorder v4.exe`.

## Release naming

Keep every release executable in `dist` and name them `GD Recorder v1.exe`, `GD Recorder v2.exe`, and so on. The current release is v4; name the next new build v5 and increment the number for each future release.

## Android port

The separate Android project and build/install instructions are in [android/README.md](android/README.md). Android APK releases use their own sequence, starting with v1.

The bundled capture engine is [wcap](https://github.com/mmozeiko/wcap), released under the Unlicense; the English OCR data is from [tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), under Apache 2.0. Notices are in the license files.

## License

Original project code and documentation are licensed under [MIT](LICENSE). Bundled third-party components keep their own licenses; see `WCAP-LICENSE.txt`, `TESSDATA-LICENSE.txt`, and `TESSEROCR-LICENSE.txt`.
