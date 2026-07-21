plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tapogotchi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tapogotchi"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

// Zero dependencies, zero vendor AARs, zero binary assets:
// every chirp is synthesized at first launch, every sprite is string art.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
