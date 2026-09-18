# AttendanceFR

Fully offline Android app for classroom attendance using **on-device facial recognition**.

AttendanceFR never calls a network API in the core flow. Face detection (ML Kit), face embeddings (TensorFlow Lite / MobileFaceNet), student records (Room/SQLite), and Excel export (Apache POI) all run locally on the device.

**Version:** 0.1.0
**Package:** `com.attendancefr`
**Min SDK:** 24 (Android 7.0) · **Target / compile SDK:** 35
**Language:** Kotlin · **UI:** Jetpack Compose · **Architecture:** MVVM + Repository · **DI:** Hilt

---

## Features

- **Student enrollment** — name, roll/student ID, class/section, plus 3–5 guided face photos (straight / slight left / slight right / optional chin-up). Each still is gated by ML Kit (one face, centered, eyes open, head pose) and a local lighting/blur check before an embedding is stored.
- **Live attendance** — CameraX preview with a real-time face bounding box. Capture (or optional auto-trigger) crops the face, embeds it, and matches with cosine similarity against every enrolled embedding.
- **Conservative matching** — default threshold **0.60**. Below-threshold results are flagged as *Unknown face* and never silently assigned. Duplicate marks for the same student on the same local date are rejected with “already marked”.
- **Manual override** — searchable list to mark Present / Late / Absent by hand, always available as a fallback independent of the camera.
- **Reports** — attendance rate per student over a date range and class filter. Export a real `.xlsx` workbook (Records + Summary sheets) via Apache POI, then share it with the system share sheet (email, Drive, Bluetooth, USB, Files — no connectivity assumed).
- **Settings** — confidence threshold slider, class/section CRUD, full Room database backup/restore (`.afrbak`) for device reset protection and manual multi-device copy.
- **Offline by design** — no `INTERNET` permission. ML Kit uses the bundled Play Services face-detection artifact. The TFLite model is packaged as an asset.

---

## Screenshots

Place PNG captures in `docs/screenshots/` and link them here after the first device run.

| Students | Enroll | Take attendance | Reports | Settings |
| --- | --- | --- | --- | --- |
| ![Students](docs/screenshots/students.png) | ![Enroll](docs/screenshots/enroll.png) | ![Attendance](docs/screenshots/attendance.png) | ![Reports](docs/screenshots/reports.png) | ![Settings](docs/screenshots/settings.png) |

---

## How the pipeline works (one sentence)

Camera frame → ML Kit face box + quality gate → crop/align 112×112 → MobileFaceNet TFLite embedding → cosine similarity vs Room gallery → Present / Unknown / Already marked → optional Excel export.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the diagram and class map, [SETUP.md](SETUP.md) for model placement, [ACCURACY_NOTES.md](ACCURACY_NOTES.md) for the 90%+ deployment recipe, and [DATA_MODEL.md](DATA_MODEL.md) for the Room schema.

---

## Minimum device requirements

| Item | Requirement |
| --- | --- |
| OS | Android 7.0 (API 24) or newer |
| Camera | Front camera preferred; back camera is used as fallback |
| RAM | 3 GB+ recommended (2 GB may work with `largeHeap`) |
| CPU | Mid-range 2021-era tablet is the design target (e.g. Helio P / Snapdragon 4xx–6xx). Match is budgeted at **&lt; 1 s** per still. |
| Storage | ~40 MB APK + Room DB. Keep tens of MB free for Excel exports and backups. |
| Lighting | Normal classroom lighting. Harsh backlight and total darkness will be rejected with an on-screen hint. |
| Emulator | Camera-capable AVD **or** a physical device. Face matching on the emulator is only useful if you inject a webcam / scene images. |

Google Play Services is **not** required at runtime because the app depends on `play-services-mlkit-face-detection`, which embeds the face model in the APK.

---

## Build and run from source

You need Android Studio Ladybug (2024.2.1) or newer, JDK 17, and Android SDK 35. Full steps including the TFLite model are in [SETUP.md](SETUP.md). Short version:

```bash
# 1. Open the project in Android Studio, or from a terminal:
#    (generate the Gradle wrapper JAR the first time — see SETUP.md)
gradle wrapper --gradle-version 8.9

# 2. Drop the model in place
#    app/src/main/assets/mobile_face_net.tflite

# 3. Build a debug APK
./gradlew :app:assembleDebug

# 4. Install on a device with USB debugging
./gradlew :app:installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Grant **Camera** when prompted. There is no login, no cloud, and no first-run download.

### First-run checklist

1. Settings → confirm “mobile_face_net.tflite loaded”.
2. Students → Enroll → create a class, capture 3+ poses, Save.
3. Take → Capture & match the same person. You should see a green check and the name.
4. Reports → Export Excel → Share.

---

## Project layout

```
AttendanceFR/
├── README.md, ARCHITECTURE.md, SETUP.md, ACCURACY_NOTES.md, DATA_MODEL.md, CHANGELOG.md
├── app/src/main/java/com/attendancefr/
│   ├── data/          Room, repositories, backup, Excel
│   ├── domain/model/  UI-facing models
│   ├── ml/            ML Kit helper, TFLite engine, matcher, quality
│   ├── di/            Hilt module
│   └── ui/            Compose screens, camera, theme, navigation
└── app/src/main/assets/mobile_face_net.tflite   # you add this (see SETUP.md)
```

---

## Privacy

- No analytics, no crash reporter, no ads, no `INTERNET` permission.
- Face embeddings are float vectors stored in SQLite on the device. Source photos are **not** kept after enrollment.
- Backup files contain embeddings and attendance records. Treat `.afrbak` like student PII.

---

## License

Application code is provided as-is for the AttendanceFR project. The MobileFaceNet TFLite weights have their **own** license — do not redistribute the `.tflite` file until you have confirmed it. ML Kit, TensorFlow Lite, CameraX, Room, Hilt, Compose, and Apache POI are used under their respective licenses.
