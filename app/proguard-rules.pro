# ---- TensorFlow Lite -------------------------------------------------------
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ---- ML Kit ----------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-dontwarn com.google.mlkit.**

# ---- Apache POI / XMLBeans ------------------------------------------------
# POI is reflection-heavy. The rules below are the well-known Android minimum.
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn com.microsoft.schemas.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.w3c.dom.**
-dontwarn org.ietf.jgss.**

# ---- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger ---------------------------------------------------------
-keep,allowobfuscation @interface dagger.hilt.**

# ---- Kotlin serialization of embeddings -----------------------------------
# Keep data classes holding FloatArray blobs (Room entities) intact.
-keepclassmembers class com.attendancefr.data.local.entity.** { *; }
