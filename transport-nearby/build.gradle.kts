// Transport Nearby Connections (P2P_CLUSTER).
//
// La logique (NearbyTransport, PermissionPolicy, cycles de diffusion) est du Kotlin pur, testée
// sur la JVM derrière l'interface NearbyApi ; seul gms/GmsNearbyApi touche l'API Android.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.netpopup.transport.nearby"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Transport, TransportManager et les types de la messagerie font partie de l'API publique
    api(project(":core-dtn"))

    implementation(libs.play.services.nearby)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
