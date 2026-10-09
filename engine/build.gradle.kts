plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aamus.powerpdf.engine"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Instrumented tests (engine/src/androidTest) run MuPDF itself on the emulator (docs/testing.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // MuPDF is AGPL; it is used only inside this module (docs/decisions.md, "Engine").
    implementation(libs.mupdf.fitz)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    // The image tools (decisions §34): upright photos, and HEIC written by the phone's own encoder.
    implementation(libs.exifinterface)
    implementation(libs.heifwriter)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
