plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nobiownvm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nobiownvm"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // REQUIRED for the QEMU-as-.so trick: on Android 10+ a binary in app
    // storage cannot be exec'd (SELinux app_data_file label). A native
    // library in jniLibs is installed with an exec_type label, so the
    // QEMU binary MUST ship as jniLibs/arm64-v8a/libqemu.so and must be
    // extracted to disk, not loaded from the compressed APK.
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
}
