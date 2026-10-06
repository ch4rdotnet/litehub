plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chardidathing.litehub.webui"
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
    api(project(":core:config"))
    implementation(libs.nanohttpd)
    implementation(libs.kotlinx.coroutines.android)
}
