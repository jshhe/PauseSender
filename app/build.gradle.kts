plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pause.sender"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pause.sender"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "zh-rCN")
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        enable += setOf("MissingTranslation", "ExtraTranslation", "StringFormatMatches")
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    // 1.0.4+ requires compileSdk 37; 1.0.3 exposes the same API on compileSdk 35.
    //noinspection GradleDependency
    implementation("com.qmdeve.liquidglass:core:1.0.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
