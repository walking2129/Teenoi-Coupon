plugins {
    id("com.android.application")
}
android {
    namespace = "com.kim.autobuyer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kim.autobuyer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
