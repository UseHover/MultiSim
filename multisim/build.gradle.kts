plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dagger.hilt.android.plugin")
    id("com.google.devtools.ksp")
    kotlin("kapt")
}

android {

    namespace = "com.hover.multisim"

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

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    dependencies {
        implementation(libs.android.appcompat)

        implementation(libs.android.localbroadcastmanager)

        implementation(libs.android.work)

        implementation(libs.sentry)

        implementation(libs.bundles.hilt)

        implementation(libs.bundles.room)
        ksp(libs.room.compiler)

        testImplementation(libs.test.junit4)
        testImplementation(libs.kotlin.coroutines.test)
        testImplementation(libs.test.robolectric)
        testImplementation(libs.test.mockk)
        testImplementation(libs.test.fixture)
    }
}

kotlin {
    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }
    }
}
