plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "dev.colorcode.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.colorcode.app"
        manifestPlaceholders["appLabel"] = "ColorCode"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("COLORCODE_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("COLORCODE_VERSION_NAME").orNull ?: "0.1.0"
    }
    signingConfigs {
        create("published") {
            storeFile = providers.environmentVariable("COLORCODE_KEYSTORE_PATH").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("COLORCODE_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("COLORCODE_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("COLORCODE_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "ColorCode Dev"
        }
        release { signingConfig = signingConfigs.getByName("published") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
