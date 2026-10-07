plugins {
    alias(libs.plugins.android.application)
}

// release signing only ever comes from the environment, which ci fills from its protected
// secrets. without KEYSTORE_PATH the release apk is left unsigned, as local and pr builds are
val releaseKeystore: String? = System.getenv("KEYSTORE_PATH")

// the version is the release tag, ci passes v1.2.3 in as VERSION_NAME=1.2.3 and the code is
// 10203, so a tag is the only place a version is written. local builds are 0.0.0-dev
val appVersion: String = System.getenv("VERSION_NAME") ?: "0.0.0-dev"
val appVersionCode: Int = run {
    val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersion)?.destructured
        ?: error("VERSION_NAME $appVersion isn't a version like 1.2.3")
    require(minor.toInt() < 100 && patch.toInt() < 100) { "minor and patch have to stay under 100 to fit the version code" }
    (major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()).coerceAtLeast(1)
}

android {
    namespace = "com.chardidathing.litehub"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.chardidathing.litehub"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
        // where the updater looks for new releases
        buildConfigField("String", "UPDATE_REPO", "\"ch4rdotnet/litehub\"")
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
