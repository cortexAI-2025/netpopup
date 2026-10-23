import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Transport « relais Internet » (mesh-24, issue #25) : un relais muet d'enveloppes opaques derrière l'interface
// Transport. Kotlin pur, sans SDK Firebase : le relais est joint par REST (HttpURLConnection), donc testable sur la JVM.
//
// Ce module n'est PAS une dépendance de :app. L'application livrée n'a pas la permission INTERNET (testé en CI,
// ManifestPermissionsTest) ; l'y relier est une décision à part (voir docs/internet-relay.md).
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
    // org.json fait partie d'Android : en dépendance de compilation seulement, pour ne pas le doubler dans l'APK.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}
