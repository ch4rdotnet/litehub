plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.chardidathing.litehub"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.chardidathing.litehub"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:config"))
    implementation(project(":source:ha"))
    implementation(project(":source:calendar"))
    implementation(project(":source:feed"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:editor"))
    implementation(libs.kotlinx.coroutines.android)
}
