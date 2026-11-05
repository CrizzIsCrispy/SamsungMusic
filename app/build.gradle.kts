plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.crizz.samsungmusic"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.crizz.samsungmusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
