plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** The Media3 release Plezy (which plays the owner's Dolby Vision files) is on, or newer. */
val NEWER_MEDIA3 = "1.11.1"

android {
    namespace = "com.biskyt.myplexplayer.dvdiag"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.biskyt.myplexplayer.dvdiag"
        minSdk = 26
        targetSdk = 34 // so Back reaches the activity as a key on any Android
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Two builds that install side by side: the player library MyPlexPlayer uses, and the newer one Plezy uses, to
    // show whether upgrading alone changes how Dolby Vision plays.
    flavorDimensions += "media3"
    productFlavors {
        create("app") {
            dimension = "media3"
            applicationIdSuffix = ".app"
            resValue("string", "app_name", "DV diagnostics (Media3 ${libs.versions.media3.get()})")
        }
        create("newer") {
            dimension = "media3"
            applicationIdSuffix = ".newer"
            resValue("string", "app_name", "DV diagnostics (Media3 $NEWER_MEDIA3)")
        }
    }
    // The app's own shared test key, so the debug build installs without one of its own.
    signingConfigs.getByName("debug") { storeFile = file("../../../app/debug.keystore") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation(libs.zxing.core)
    implementation(libs.coroutines.android)
    // MyPlexPlayer's Media3. Not its FFmpeg decoders: there are none for the newer Media3 (see ProbeRenderersFactory).
    "appImplementation"(libs.media3.exoplayer)
    "newerImplementation"("androidx.media3:media3-exoplayer:$NEWER_MEDIA3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
