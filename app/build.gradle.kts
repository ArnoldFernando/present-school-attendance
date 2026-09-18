plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// POI 4.1.2 contains CleanerUtil which uses MethodHandle.invoke,
// unsupported by Android D8 on API < 26. We strip that single class
// and repackage the jar before dexing. POI falls back safely for
// our write-only Excel use case (no memory-mapped file cleaning needed).
val patchPoi by tasks.registering(Zip::class) {
    description = "Removes CleanerUtil.class from poi-4.1.2.jar for Android API 24 compatibility"
    group = "build"

    val poiConfig = configurations.detachedConfiguration(
        dependencies.create("org.apache.poi:poi:4.1.2")
    ).apply {
        isTransitive = false   // <-- only fetch poi-4.1.2.jar, not its dependencies
    }
    val poiJar = poiConfig.singleFile

    inputs.file(poiJar)
    from(zipTree(poiJar)) {
        exclude("org/apache/poi/poifs/nio/CleanerUtil.class")
    }

    archiveFileName.set("poi-4.1.2-patched.jar")
    destinationDirectory.set(layout.buildDirectory.dir("patched-jars"))
}

android {
    namespace = "com.attendancefr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.attendancefr"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Required by Apache POI on Android (java.time / some nio APIs).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
            pickFirsts += setOf(
                "META-INF/services/javax.xml.stream.XMLEventFactory",
                "META-INF/services/javax.xml.stream.XMLInputFactory",
                "META-INF/services/javax.xml.stream.XMLOutputFactory"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("tflite", "lite")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.face.detection)

    implementation(libs.tflite)
    implementation(libs.tflite.support)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

        implementation(libs.poi.ooxml) {
        exclude(group = "org.apache.poi", module = "poi")
        exclude(group = "stax", module = "stax-api")
        exclude(group = "xml-apis")
    }
     implementation(files(patchPoi.map { it.archiveFile }))
    // Quiet POI logging without log4j-core / log4j-api MethodHandle issues on Android 24.
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
