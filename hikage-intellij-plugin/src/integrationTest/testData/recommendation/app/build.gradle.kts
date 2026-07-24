plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.highcapable.hikage.integration"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.highcapable.hikage.integration"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}