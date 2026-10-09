plugins {
    alias(libs.plugins.android.library)
}

// Text recognition (docs/research-ocr-android.md): the desktop's PaddleOCR models on onnxruntime.
// Only this module imports onnxruntime; the models are its assets.
android {
    namespace = "com.aamus.powerpdf.ocr"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // The models are read whole on each open; stored as they are, not inflated every time.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}
