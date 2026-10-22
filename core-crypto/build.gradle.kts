import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Crypto E2E. libsignal-client (JVM) n'est que compileOnly : l'app embarque libsignal-android,
// qui expose les mêmes classes avec les bibliothèques natives Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    compileOnly(libs.libsignal.client)
    testImplementation(libs.libsignal.client)

    testImplementation(libs.junit)
}
