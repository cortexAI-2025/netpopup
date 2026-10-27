import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "com.netpopup"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.netpopup"
        minSdk          = 29
        targetSdk       = 34
        versionCode     = 3
        versionName     = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Standalone APK per CPU; do not make phone users download emulator/other-phone natives.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    // Lire keystore.properties (jamais commité — voir .gitignore).
    // Si absent (CI sans signing), le release build reste non signé.
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply {
                    load(propsFile.inputStream())
                }
                storeFile     = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias      = props.getProperty("keyAlias")
                keyPassword   = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable      = false          // jamais de debug en production
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable      = true
            applicationIdSuffix = ".debug"    // APK debug installable en parallèle
            versionNameSuffix   = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Exigé par libsignal-android (java.time, etc.)
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Robolectric charge le manifeste fusionné et les ressources : les tests d'interface tournent sur la JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    composeOptions {
        // Compose compiler pour Kotlin 1.9.22
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // libsignal's Java resources also contain desktop binaries. Android uses lib/<abi>/*.so.
            excludes += setOf("**/*.dylib", "**/*.dll")
        }
        // Variante de test de libsignal (~65 Mo par ABI) : inutile dans l'application livrée
        jniLibs { excludes += "**/libsignal_jni_testing.so" }
    }
}

dependencies {
    // Mesh DTN
    implementation(project(":core-dtn"))
    implementation(project(":core-crypto"))
    implementation(project(":store-sql"))
    implementation(project(":mesh-engine"))
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite) // supertype de SQLiteDatabase (SQLCipher) : requis pour compiler
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":transport-nearby"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — versions gérées centralement
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore (local user prefs)
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
