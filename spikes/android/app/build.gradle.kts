plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.biskyt.myplexplayer.spike"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.biskyt.myplexplayer.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // phones, and the emulator
    }
    // The app's own shared test key, so the debug build installs without one of its own.
    signingConfigs.getByName("debug") { storeFile = file("../../../app/debug.keystore") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val spikeSource = layout.buildDirectory.dir("spike-python")
val copySpike = tasks.register<Sync>("copySpike") {
    from("../../plex_spike.py")
    into(spikeSource)
}
tasks.matching { it.name.endsWith("PythonSources") }.configureEach { dependsOn(copySpike) }

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

chaquopy {
    defaultConfig {
        version = "3.12"
        pyc { src = false } // no Python needed on the build machine to compile it
    }
    // The spike itself, copied in at build time: the app runs exactly what's in spikes/.
    sourceSets.getByName("main") { srcDir(spikeSource) }
}

dependencies {
    implementation("androidx.core:core:1.16.0") // FileProvider, to share the results zip
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
