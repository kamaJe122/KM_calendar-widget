plugins {
    id("com.android.application")
}

val releaseKeystoreFile = rootProject.file("km-calwidget-release.jks")
val hasReleaseKeystore = releaseKeystoreFile.exists()

android {
    namespace = "io.github.kamaje122.calwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kamaje122.calwidget"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "2.0.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("CALWIDGET_STORE_PASS") ?: "calwidget2026"
                keyAlias = System.getenv("CALWIDGET_KEY_ALIAS") ?: "calwidget"
                keyPassword = System.getenv("CALWIDGET_KEY_PASS") ?: "calwidget2026"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Pure Android SDK (zero external bloat, ultra-lightweight APK)
}
