plugins {
    id("com.android.application")
}
android {
    namespace = "com.kim.autobuyer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kim.autobuyer.next"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "2.0"
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storeType = "JKS"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core:1.15.0")
}
