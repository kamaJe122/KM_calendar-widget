import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseKeystoreFile = rootProject.file(
    keystoreProps.getProperty("storeFile") ?: "km-calwidget-release.jks"
)
val storePass = System.getenv("CALWIDGET_STORE_PASS") ?: keystoreProps.getProperty("storePassword")
val aliasName = System.getenv("CALWIDGET_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias") ?: "calwidget"
val keyPass = System.getenv("CALWIDGET_KEY_PASS") ?: keystoreProps.getProperty("keyPassword")
val hasReleaseKeystore = releaseKeystoreFile.exists() && !storePass.isNullOrBlank() && !keyPass.isNullOrBlank()

android {
    namespace = "io.github.kamaje122.calwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kamaje122.calwidget"
        minSdk = 31
        targetSdk = 36
        versionCode = 18
        versionName = "3.5.2"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
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
