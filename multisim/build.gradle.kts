plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {

    compileSdk = 33

    defaultConfig {
        minSdk = 18
        targetSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
    namespace = "com.hover.multisim"

    dependencies {
        implementation(libs.android.appcompat)
        implementation(libs.android.localbroadcastmanager)
        implementation(libs.android.work)
        implementation(libs.sentry)

        testImplementation(libs.test.junit4)
    }
}
