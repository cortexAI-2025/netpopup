import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Cœur DTN — Kotlin pur, sans Android ni Firebase, testable sur la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation(project(":core-crypto"))
    // Types de clés dans l'API de :core-crypto ; à l'exécution, l'app fournit libsignal-android.
    compileOnly(libs.libsignal.client)
    testImplementation(libs.libsignal.client)

    // Flow fait partie de l'API publique (ChatRepository)
    api(libs.kotlinx.coroutines.core)

    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
