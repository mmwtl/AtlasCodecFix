plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val secureSigningScript = providers.gradleProperty("secure.signing")
    .orNull
    ?.let(rootProject::file)
val hasReleaseSigning = secureSigningScript?.isFile == true
val appVersionCode = 18
val appVersionName = "1.2.7"

base {
    archivesName.set("$appVersionName[$appVersionCode]AtlasCodecFix")
}

android {
    namespace = "com.mmwtl.atlascodecfix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mmwtl.atlascodecfix"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("hevc"))
        }
    }

    androidResources {
        ignoreAssetsPatterns.add("default")
        ignoreAssetsPatterns.add("tests")
    }

    lint {
        lintConfig = rootProject.file("lint.xml")
    }

    if (hasReleaseSigning) {
        apply(secureSigningScript!!)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

if (!hasReleaseSigning) {
    tasks.configureEach {
        if (name == "packageRelease" || name == "bundleRelease") {
            doFirst {
                throw GradleException(
                    "Release signing is required. Configure secure.signing or build the debug variant."
                )
            }
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

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
