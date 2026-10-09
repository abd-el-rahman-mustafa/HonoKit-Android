pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF for Android (AGPL) is published only by Artifex.
        maven("https://maven.ghostscript.com") {
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}

rootProject.name = "PowerPDF"
include(":app")
include(":engine")
include(":ocr")
