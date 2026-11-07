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
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
