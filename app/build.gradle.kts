import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key lives outside the repo (docs/decisions.md, "Signing"). Its properties file
// is found through POWERPDF_KEYSTORE_PROPERTIES or at ~/.android-keys/powerpdf/keystore.properties.
val keystoreProperties: Properties? = run {
    val path = System.getenv("POWERPDF_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android-keys/powerpdf/keystore.properties"
    val file = File(path)
    if (!file.isFile) return@run null
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.aamus.powerpdf"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // Permanent: Android only updates an app with the same id and the same signing key.
        // com.aamus.honokit since 0.6.0 (decisions §38); the code's namespace stays com.aamus.powerpdf.
        applicationId = "com.aamus.honokit"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.10.8"

        // A rehearsal build reads updates from a feed on this PC instead of GitHub (docs/testing.md,
        // "Rehearsing an update"); every other build reads GitHub. Plain http is allowed only then:
        // the emulator reaches this PC at http://10.0.2.2.
        val updateFeed = (project.findProperty("updateFeed") as String?).orEmpty()
        buildConfigField("String", "UPDATE_FEED", "\"$updateFeed\"")
        manifestPlaceholders["cleartext"] = updateFeed.startsWith("http://").toString()
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v2 for Android 8+, v3 so the key could be rotated one day (docs/decisions.md §5).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Phones only: 64-bit and older 32-bit ARM, one APK for all (decisions §6).
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        debug {
            applicationIdSuffix = ".dev"
            // Its own name in Open with and the launcher, so it is never taken for the real app.
            manifestPlaceholders["appLabel"] = "HonoKit Dev"
            versionNameSuffix = "-dev"
            // Also x86_64, for emulators.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Lets Android 13+ list English and Arabic under Settings > Apps > Language.
        generateLocaleConfig = true
    }

    packaging {
        // Compressed in the APK (a smaller file to send); Android unpacks them when installing.
        jniLibs.useLegacyPackaging = true
        // Text recognition is for 64-bit phones only (owner, 2026-10-01; decisions §29): the
        // 32-bit onnxruntime (23 MB) stays out; everything else still installs on 32-bit phones.
        jniLibs.excludes += listOf("lib/armeabi-v7a/libonnxruntime.so", "lib/armeabi-v7a/libonnxruntime4j_jni.so")
        resources.excludes += listOf("META-INF/*.version", "META-INF/**/LICENSE*", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK (F-Droid/IzzyOnDroid ask for this).
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":engine"))
    // Text recognition (docs/research-ocr-android.md); only :ocr imports onnxruntime.
    implementation(project(":ocr"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.exifinterface)
    // The scanner's own camera (docs/research-scan.md M5); works without Google Play services.
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    testImplementation(libs.junit)
    // Real org.json for UpdateFeedTest: android.jar only has stubs.
    testImplementation(libs.org.json)
}
