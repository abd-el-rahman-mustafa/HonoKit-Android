buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself; this pins the Kotlin version it uses.
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
