pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NetPopUp"
include(":app")

// Mesh DTN v0 — voir le document « NetPopUp — Architecture mesh v0.1 »
include(":core-dtn")          // Kotlin pur : enveloppe, store, routage, ChatRepository
include(":core-crypto")       // Kotlin pur : identité, X3DH, Double Ratchet (libsignal)
include(":transport-nearby")  // Android : Transport via Nearby Connections
include(":store-sql")        // Kotlin pur : schéma SQL, backend du store, états libsignal
include(":mesh-engine")     // Kotlin pur : MeshChatRepository, assemble toutes les briques
