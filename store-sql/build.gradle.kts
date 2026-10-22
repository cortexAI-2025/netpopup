import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Persistance SQL : schéma, backend du store d'enveloppes, blocages et états libsignal.
// Kotlin pur : l'app fournit SQLCipher, les tests de la JVM utilisent SQLite (JDBC).
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
    compileOnly(libs.libsignal.client)

    testImplementation(libs.libsignal.client)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.junit)
}
