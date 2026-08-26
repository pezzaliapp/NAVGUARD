plugins {
    id("com.android.application")
}

android {
    namespace = "it.alessandropezzali.navguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.alessandropezzali.navguard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
