plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {

    namespace = "com.hover.app"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.hover.app"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {

    implementation(project(":multisim"))

    implementation(libs.android.coreKtx)
    implementation(libs.android.appcompat)
    implementation(libs.android.material)

    androidTestImplementation(libs.test.android.junit)
    androidTestImplementation(libs.test.android.espresso)

    testImplementation(libs.test.junit4)
}