plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "ir.sabou.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.sabou.erp"
        // 26: PBKDF2WithHmacSHA256, java.util.Base64 and ThreadLocal.withInitial used by the core exist natively.
        minSdk = 26
        targetSdk = 36
        // Every CI build gets a higher versionCode, so a newer APK installs over the previous one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes only from the environment; a partial configuration is an error.
    val signing = listOf("SABOU_KEYSTORE_PATH", "SABOU_KEYSTORE_PASSWORD", "SABOU_KEY_ALIAS", "SABOU_KEY_PASSWORD")
        .associateWith { providers.environmentVariable(it).orNull }
    val signingComplete = signing.values.all { !it.isNullOrBlank() }
    if (signing.values.any { !it.isNullOrBlank() } && !signingComplete) {
        throw GradleException("Release signing is partially configured: set all SABOU_* signing variables or none.")
    }
    signingConfigs {
        // A fixed, public debug key (committed on purpose, debug builds only): without it every CI runner
        // signs with a fresh random key and Android refuses to update the installed app.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (signingComplete) {
            create("release") {
                storeFile = file(signing.getValue("SABOU_KEYSTORE_PATH")!!)
                storePassword = signing.getValue("SABOU_KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("SABOU_KEY_ALIAS")
                keyPassword = signing.getValue("SABOU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingComplete) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:backup"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite:2.6.2")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
