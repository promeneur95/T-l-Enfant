plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vc = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "fr.promeneur.telenfant"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.promeneur.telenfant"
        minSdk = 29
        targetSdk = 35
        versionCode = vc
        versionName = "1.0.$vc"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "telenfant"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
