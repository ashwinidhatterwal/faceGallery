plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Private upload credentials are provided only by the owner's local environment or CI secrets.
val releaseCredentials = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
val releaseValues = releaseCredentials.associateWith { providers.environmentVariable(it).orNull }
val signedRelease = releaseValues.values.all { !it.isNullOrBlank() }
check(releaseValues.values.all { it.isNullOrBlank() } || signedRelease) {
    "Release signing requires all four RELEASE_* variables. Never use the public debug key for Play."
}

android {
    namespace = "com.mosaic.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mosaic.gallery"
        minSdk = 28
        targetSdk = 36
        versionCode = 45
        versionName = "1.0.0-rc5"
    }

    // The release is signed only with credentials supplied at build time.
    // No production or debug keystore is checked into the repository.
    signingConfigs {
        if (signedRelease) create("upload") {
            storeFile = file(releaseValues.getValue("RELEASE_STORE_FILE")!!)
            storePassword = releaseValues.getValue("RELEASE_STORE_PASSWORD")
            keyAlias = releaseValues.getValue("RELEASE_KEY_ALIAS")
            keyPassword = releaseValues.getValue("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signedRelease) signingConfig = signingConfigs.getByName("upload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    androidResources { noCompress += "tflite" }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}
