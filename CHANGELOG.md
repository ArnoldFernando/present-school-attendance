# Changelog

All notable changes to AttendanceFR are documented in this file.

## [0.1.0] — 2025-04-12

Initial production-quality scaffold of the fully offline attendance app.

### Added

- Kotlin + Jetpack Compose single-module app, minSdk 24, targetSdk 35.
- MVVM + Repository architecture with Hilt, Coroutines, and Flow.
- Room database: `students`, `face_embeddings`, `attendance_records`, `class_sections` (v1).
- CameraX live preview with ML Kit on-device face detection and bounding-box overlay.
- Still-image quality gate: one face, centered, eyes open, pose window, luma, sharpness.
- TensorFlow Lite `FaceEmbeddingEngine` for bundled `mobile_face_net.tflite` (112×112, 128- or 512-d, L2-normalized).
- Cosine-similarity matcher with per-student max-over-shots and a DataStore-backed threshold (default 0.60).
- Enrollment flow: name, roll, class, 3–5 guided poses, save / re-enroll / edit / delete.
- Attendance flow: capture or optional auto-trigger, Present confirmation, already-marked guard, Unknown-face dialog that never guesses.
- Manual override screen (Present / Late / Absent) as a camera-independent fallback.
- Reports: per-student rates over a date range and class filter.
- Apache POI `.xlsx` export (Records + Summary) to app storage, shareable via FileProvider.
- Settings: threshold slider, class CRUD, on-device model status, full DB backup/restore (`.afrbak`).
- Camera permission gate, empty states, user-facing hints for no face / multiple faces / poor lighting.
- Unit tests for cosine ranking, embedding codec, and status parsing.
- Project docs: README, ARCHITECTURE, SETUP, ACCURACY_NOTES, DATA_MODEL, CHANGELOG.

### Notes

- The TFLite weights are **not** shipped in git; drop `mobile_face_net.tflite` into `app/src/main/assets/` (see SETUP.md).
- Matching is 1:N against the full enrolled gallery, not filtered by the selected class (documented assumption).
- ML Kit eyes-open + pose are liveness *signals*, not anti-spoofing.
