// The spike as a phone app: runs spikes/plex_spike.py (unchanged, through Chaquopy) so the owner can test their
// Plex server from their phone. A build of its own, outside the app's; from the repository's root:
// `./gradlew -p spikes/android assembleDebug`.
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
    // The app's versions, so this build moves with them.
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}

rootProject.name = "PlexSpike"
include(":app")
