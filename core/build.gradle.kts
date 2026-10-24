plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Test helpers the app's tests share (RegexPortability).
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    // Android ships org.xmlpull in the framework; kxml2 provides the same API for JVM tests.
    compileOnly(libs.kxml2)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2)
    testFixturesImplementation(libs.junit)
}

// PlexServerTest needs a real Plex Media Server (.github/scripts/start-plex.sh), so it has a task of its own:
// ./gradlew :core:plexServerTest [-PplexServer=http://127.0.0.1:32400]. The Plex spike workflow runs it.
val plexServerTest = "**/PlexServerTest*"
tasks.test { exclude(plexServerTest) }
// Nor in coverage: the report would otherwise run it, and CI's build has no Plex server.
kover { currentProject { instrumentation { disabledForTestTasks.add("plexServerTest") } } }
tasks.register<Test>("plexServerTest") {
    description = "Runs PlexServerTest against a real Plex Media Server."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include(plexServerTest)
    systemProperty("plex.server", providers.gradleProperty("plexServer").getOrElse("http://127.0.0.1:32400"))
    // The server, not the code, is what's tested: never up to date, and never replayed from the build cache.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
