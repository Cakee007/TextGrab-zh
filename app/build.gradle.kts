import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.noah.textgrab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.noah.textgrab.zh"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3-zh"
        // Keep only the languages this fork actually ships. Note these are
        // language qualifiers ("zh"), not region-specific resource folder
        // names ("zh-rCN") — using the folder name here silently drops the
        // whole locale from the build.
        //
        // The defaults in values/ are Chinese too, so the app stays Chinese
        // whatever the system language is; values-en keeps an English build
        // possible for anyone who wants it.
        resourceConfigurations += listOf("zh", "en")
    }

    // Signing credentials live in keystore/keystore.properties (not in git).
    // Without that file the release build is simply unsigned.
    //
    // CI build: the workflow decodes SIGNING_KEY_BASE64 into a keystore and
    // passes the passwords as environment variables, so no file is committed.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val envStore = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        } else if (!envStore.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(envStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // On-device neural text recognition, models bundled in the APK — no Play
    // Services needed.
    //
    // Both packs are shipped because they are not interchangeable: the Chinese
    // pack is trained on hanzi and reads Latin/digits only as a side effect,
    // while the Latin pack is the accurate one for numbers, English and
    // symbols. OcrEngine decides per region which pack to use.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
