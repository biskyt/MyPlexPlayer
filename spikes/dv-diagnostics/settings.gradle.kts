// The Dolby Vision diagnostics app: a report on what the TV's decoders and display support, and test plays of a title
// from the owner's Plex server, to find why MyPlexPlayer plays Dolby Vision files as HDR10. A build of its own,
// outside the app's; from the repository's root: `./gradlew -p spikes/dv-diagnostics assembleDebug`.
pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: avoids Central's rate limiting on busy CI/cloud IPs.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
    // The app's versions, so the "app" build plays as MyPlexPlayer does.
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "DvDiagnostics"
include(":app")
