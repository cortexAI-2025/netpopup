import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Crypto E2E — Kotlin pur. libsignal y sera ajouté à l'issue 9.
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
    testImplementation(libs.junit)
}
