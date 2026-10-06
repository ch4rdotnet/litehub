plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chardidathing.litehub.ui.widgets"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":ui:components"))
}
