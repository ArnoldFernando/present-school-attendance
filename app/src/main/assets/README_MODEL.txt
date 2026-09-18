Place the MobileFaceNet TensorFlow Lite model here as:

    mobile_face_net.tflite

The app looks for this exact filename at runtime (see FaceEmbeddingEngine).
It is NOT committed to git because the model is several megabytes and is
redistributed under its own license.

How to obtain the model is documented in SETUP.md at the project root.

Until the file is present the app still builds and runs. Enrollment and
attendance capture will surface a clear "model missing" error instead of
crashing.
