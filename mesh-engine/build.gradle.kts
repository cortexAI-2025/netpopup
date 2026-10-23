import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Moteur du mesh : assemble store, synchronisation, transports, crypto, rooms et ACK derrière
// ChatRepository. Kotlin pur : l'app lui fournit SQLCipher et les transports Android.
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
    api(project(":core-dtn"))
    api(project(":core-crypto"))
    api(project(":store-sql"))
    compileOnly(libs.libsignal.client)

    testImplementation(testFixtures(project(":store-sql")))
    testImplementation(libs.libsignal.client)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlinx.coroutines.test)
    // Le relais Internet n'est qu'un banc d'essai ici : le moteur lui-même n'en dépend pas.
    testImplementation(project(":transport-internet"))
    testImplementation(libs.junit)
}
