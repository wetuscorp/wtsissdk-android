plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

group = "co.wetus"
version = "0.1.0-alpha.1"

android {
    namespace = "co.wetus.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 23; consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
    sourceSets.getByName("test").resources.srcDir("../contracts")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.android.installreferrer:installreferrer:2.2")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("co.wetus", "wts-sdk-android", version.toString())
    pom {
        name.set("wts.is Android SDK")
        description.set("Official wts.is deep-link and mobile attribution SDK")
        inceptionYear.set("2026")
        url.set("https://github.com/wetuscorp/wtsissdk-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("wetuscorp")
                name.set("Wetus")
                url.set("https://wetus.co")
            }
        }
        scm {
            url.set("https://github.com/wetuscorp/wtsissdk-android")
            connection.set("scm:git:https://github.com/wetuscorp/wtsissdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/wetuscorp/wtsissdk-android.git")
        }
    }
}
