plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

val appVersion = java.util.Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

android {
    namespace = "ir.sabou.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.sabou.erp"
        // 26: PBKDF2WithHmacSHA256, java.util.Base64 and ThreadLocal.withInitial used by the core exist natively.
        minSdk = 26
        targetSdk = 36
        // One source of the version: version.properties (raised in the PR that prepares a release).
        versionCode = appVersion.getProperty("VERSION_CODE").toInt()
        versionName = appVersion.getProperty("VERSION_NAME")
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
        // A fixed, public debug key (committed on purpose) for the separate test app ir.sabou.erp.debug only:
        // without it every CI runner signs with a fresh random key and Android refuses to update the test app.
        // The real app (ir.sabou.erp) is signed only with the private key from the environment.
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
        // The debug app is a different app (own id, own name): it can never install over, or be mistaken for,
        // the real one, and the public debug key below can never sign an update of the real app.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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

// Debug builds from CI install over each other: their versionCode is the CI run number (always rising).
// Release builds keep the versionCode of version.properties.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val run = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
        if (run != null) variant.outputs.forEach { it.versionCode.set(run) }
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
