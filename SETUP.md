# Setup

Step-by-step instructions to build AttendanceFR from this source tree.

The app is fully offline **at runtime**. The only “download” is a one-time developer step: placing the MobileFaceNet TFLite model into `app/src/main/assets/` before you compile. That file is then bundled inside the APK.

---

## 1. Tooling

| Tool | Version |
| --- | --- |
| Android Studio | Ladybug 2024.2.1 or newer (Koala / Ladybug / Meerkat all work) |
| JDK | 17 (Android Studio’s bundled JBR is fine) |
| Android Gradle Plugin | 8.6.1 (declared in `gradle/libs.versions.toml`) |
| Gradle | 8.9 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |

SDK packages to install via SDK Manager:

- Android SDK Platform 35
- Android SDK Build-Tools 35.x
- Android SDK Platform-Tools
- Google Play services (optional; not required at runtime)

---

## 2. Get the source on disk

Copy this project folder to your machine. Then, **once**, generate the Gradle wrapper JAR if `gradle/wrapper/gradle-wrapper.jar` is missing (it is intentionally not committed as a binary in some distributions):

```bash
cd AttendanceFR
# Requires a system Gradle ≥ 8.4, or use Android Studio’s “Open” which generates it.
gradle wrapper --gradle-version 8.9
```

Android Studio → **File → Open** → select the `AttendanceFR` directory. Let it sync.

If you prefer the command line only:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS example
./gradlew :app:assembleDebug
```

---

## 3. TensorFlow Lite model (required for recognition)

### Where it goes

```
app/src/main/assets/mobile_face_net.tflite
```

The filename is hard-coded in `FaceEmbeddingEngine.MODEL_ASSET`. Do not rename it.

Until this file is present:

- The project **still compiles and launches**.
- Settings shows “Model file missing”.
- Enrollment and attendance capture fail with a clear error instead of a native crash.

`.tflite` is listed in `androidResources.noCompress` so the interpreter can memory-map it.

### What the engine expects

| Item | Value |
| --- | --- |
| Input | 1 × 112 × 112 × 3, `float32`, RGB, normalized as `(pixel / 127.5) - 1` → `[-1, 1]` |
| Output | 1 × **128** or 1 × **512** `float32` (dimension is read from the tensor at load time) |
| Post-process | L2-normalize the vector before storage / matching |

This is the MobileFaceNet convention used by the widely circulated 112×112 models.

### How to obtain a model (pick one)

The weights are **not** redistributed in this repository. You must fetch them yourself and confirm the license.

**Option A — InsightFace / buffalo MobileFaceNet TFLite (recommended starting point)**

1. Obtain a MobileFaceNet `.tflite` that takes 112×112 RGB.
2. Rename it to `mobile_face_net.tflite`.
3. Drop it in `app/src/main/assets/`.

Public sources that developers commonly use (you must verify license and input size yourself):

- [sirius-ai / MobileFaceNet_TF](https://github.com/sirius-ai/MobileFaceNet_TF) — original MobileFaceNet (TensorFlow). Convert the frozen graph with the TensorFlow Lite converter (see Option C).
- Community TFLite conversions of MobileFaceNet (search “mobilefacenet.tflite 112”). Prefer a model whose first input tensor is `[1,112,112,3]` float32.

**Option B — FaceNet 512-d (also works)**

A 160×160 FaceNet model will **not** work without code changes. If you swap in a 112×112 FaceNet-style embedding model that outputs 128 or 512 floats, `FaceEmbeddingEngine` will adapt to the output dimension automatically. If the input size is not 112, change `FaceEmbeddingEngine.INPUT_SIZE` and the preprocess in `bitmapToBuffer`.

**Option C — Convert a frozen graph yourself**

```bash
# Example only — adjust paths and input names to your graph.
python3 -m pip install tensorflow
python3 - <<'PY'
import tensorflow as tf
converter = tf.lite.TFLiteConverter.from_frozen_graph(
    "mobilefacenet_frozen.pb",
    input_arrays=["input"],
    output_arrays=["embeddings"],
    input_shapes={"input": [1, 112, 112, 3]},
)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
open("mobile_face_net.tflite", "wb").write(converter.convert())
PY
```

Then copy the file into `app/src/main/assets/`.

### Confirm the model loaded

1. Build and launch.
2. Open **Settings**.
3. “On-device model” should read `mobile_face_net.tflite loaded · 128-d embeddings` (or 512-d).

If it still says missing, check the filename (underscores, not hyphens) and rebuild — assets are packaged at compile time.

---

## 4. ML Kit face detection — no extra download

The app depends on:

```
com.google.android.gms:play-services-mlkit-face-detection:17.1.0
```

This artifact **embeds** the face-detection model in the APK (as opposed to `com.google.mlkit:face-detection`, which may lazy-download). Combined with the manifest meta-data:

```xml
<meta-data
    android:name="com.google.mlkit.vision.DEPENDENCIES"
    android:value="face" />
```

…the detector works with airplane mode on, on first launch, with no Play-services model fetch.

---

## 5. Run on a physical device (recommended)

Face recognition on a real camera is the only honest test.

```bash
# USB debugging on, device authorized
adb devices
./gradlew :app:installDebug
```

Or Android Studio → Run (Shift+F10) with the device selected.

Grant **Camera**. There is no internet permission to grant.

First-run path:

1. Settings → confirm the model loaded.
2. Students → FAB → fill name / roll / class → capture 3 poses → Save student.
3. Take → Capture & match that student.
4. Reports → Export Excel → Share.

---

## 6. Run on an emulator

Use an AVD with a camera (webcam extended control, or a canned scene).

Caveats:

- Many AVDs have no front camera. The app falls back to the back camera automatically.
- ML Kit on the emulator is slower; still captures may take >1 s. That does not represent device performance.
- Synthetic faces (the emulator scene) will not match a real enrollment. Enroll and match using the same webcam feed.

Command:

```bash
emulator -avd Pixel_7_API_35 &
./gradlew :app:installDebug
```

---

## 7. Install a prebuilt debug APK

After `./gradlew :app:assembleDebug`:

```
app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sideload on a tablet via USB / Files if you do not have adb. Unknown-sources must be allowed.

Release builds (`assembleRelease`) need a signing config you provide; none is checked in. ProGuard rules for TFLite, ML Kit, and POI are in `app/proguard-rules.pro`.

---

## 8. Apache POI on Android

`poi-ooxml` 5.3.0 is used with **core library desugaring** (`desugar_jdk_libs`) so `java.time` and related APIs work on API 24. Duplicate META-INF service files are `pickFirst`’d in `app/build.gradle.kts`. If you bump POI, re-test export on an API 24 emulator.

---

## 9. Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

Covered without a device:

- cosine similarity / ranking (`FaceMatcherTest`)
- embedding byte codec round-trip (`EmbeddingCodecTest`)
- attendance status parsing

Instrumentation tests are stubbed; camera/ML paths need a physical device.

---

## 10. Common problems

| Symptom | Fix |
| --- | --- |
| `FileNotFoundException: mobile_face_net.tflite` / Settings says missing | File not in `app/src/main/assets/` or wrong name. Rebuild. |
| `IllegalArgumentException: Cannot copy to a TensorFlowLite tensor with X bytes` | Input size mismatch. Confirm 112×112×3 float32. |
| `Cannot convert between a TensorFlowLite tensor with N bytes and a Java object` | Output dim is not 128/512, or you wrapped it wrong. Check `interpreter.getOutputTensor(0).shape()`. |
| Camera preview black | Permission denied, or emulator without a camera. |
| “No face detected” in good light | Hold at eye level, fill the guide, avoid profile shots on the *straight* pose. |
| Excel export crash on API 24 | Confirm desugaring is enabled (it is, in `app/build.gradle.kts`). |
| Restore backup does nothing | The UI restarts the process on purpose. Reopen the app. |
| `gradle-wrapper.jar` missing | `gradle wrapper --gradle-version 8.9` or open in Android Studio. |

---

## 11. Network / Play policy reminder

`AndroidManifest.xml` does **not** declare `android.permission.INTERNET`. Do not add it “just in case”. If a library you add later pulls in an HTTP client, audit it — the product requirement is zero runtime connectivity.
