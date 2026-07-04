plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mmwtl.atlascodecfix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mmwtl.atlascodecfix"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.4"

        setProperty("archivesBaseName", "$versionName[$versionCode]AtlasCodecFix")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("hevc"))
        }
    }

    if (project.hasProperty("secure.signing")) {
        val signingScript = rootProject.file(project.property("secure.signing") as String)
        if (signingScript.exists()) {
            apply(signingScript)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.adb.shell)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
