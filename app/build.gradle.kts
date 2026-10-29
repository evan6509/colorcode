plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "dev.colorcode.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.colorcode.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
