import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing details live outside the repository so keys and passwords can
// never be committed. Without this file, release builds are produced unsigned.
val signingPropertiesFile = File(
    System.getenv("DIM8_BT_THERMOMETER_SIGNING")
        ?: "${System.getProperty("user.home")}/.config/dim8-bt-thermometer/signing.properties",
)
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use(::load)
}

android {
    namespace = "com.dim8labs.btthermometer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dim8labs.btthermometer"
        // The only supported phone is the Pixel XL on Android 10. Targeting
        // API 29 keeps runtime permission and foreground-service behavior equal
        // to what is physically tested. Raising it requires the Android 12+
        // Bluetooth permission model and new physical validation.
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingPropertiesFile.isFile) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking stays off: it would change the physically tested code.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        // BuildConfig.DEBUG gates the anonymized advertisement capture log.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // API 29 is an intentional, device-specific target (see defaultConfig).
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
    }
}

dependencies {
    // No runtime libraries beyond the Kotlin standard library: API 29 framework
    // classes cover the UI, BLE, notification, and SQLite requirements.
    testImplementation("junit:junit:4.13.2")
}
