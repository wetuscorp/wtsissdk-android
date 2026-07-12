plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "co.wetus.sdk.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "co.wetus.sdk.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":sdk"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
}
