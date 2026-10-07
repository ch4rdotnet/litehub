plugins {
    alias(libs.plugins.android.application)
}

// release signing only ever comes from the environment, which ci fills from its protected
// secrets. without KEYSTORE_PATH the release apk is left unsigned, as local and pr builds are
val releaseKeystore: String? = System.getenv("KEYSTORE_PATH")

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

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    implementation(project(":source:weather"))
    implementation(project(":source:photos"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:editor"))
    implementation(project(":webui"))
    implementation(project(":dlna"))
    implementation(libs.kotlinx.coroutines.android)
}
