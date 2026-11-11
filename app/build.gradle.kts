plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freebrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.freebrowser.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "freebrowser"
            keyAlias = "freebrowser"
            keyPassword = "freebrowser"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core:1.13.1")
}
