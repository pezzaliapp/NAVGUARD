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
        versionCode = 4
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

dependencies {
    // Test-only. Nothing is added to the runtime classpath of the app.
    testImplementation("junit:junit:4.13.2")
}
