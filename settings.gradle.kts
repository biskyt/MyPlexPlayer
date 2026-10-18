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
        // Google's mirror of Maven Central: avoids Central's rate limiting on busy CI/cloud IPs.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "MyTVPlayer"

include(":core", ":app")
