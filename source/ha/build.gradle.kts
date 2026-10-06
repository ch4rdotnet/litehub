plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.chardidathing.litehub.source.ha"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
