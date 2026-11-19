plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.developer27.qvlp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.developer27.qvlp"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "1.5.0"

        // The two LED models run with PyTorch, whose library is large: ship it for 64-bit ARM phones only.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // The models are read straight from the APK.
        noCompress += "pt"
    }

    packaging {
        // Compressed in the APK and unpacked when the app is installed: PyTorch's library is 76 MB otherwise.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Kotlin & Android core libs
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.0")

    // Runs the Physical LED Detection Model and the OOK-Based LED Recognition Model (TorchScript .pt) on the phone
    implementation("org.pytorch:pytorch_android:2.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Android only stubs org.json for local unit tests; this is the real one.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
