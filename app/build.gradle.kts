plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.prayer.widget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prayer.widget"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/keystore.jks")
            storePassword = "prayertimesapp"
            keyAlias = "prayertimes"
            keyPassword = "prayertimesapp"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Standard Kotlin & Android Framework
}
