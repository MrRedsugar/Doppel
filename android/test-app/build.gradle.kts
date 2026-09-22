plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.doppel.testapp"; compileSdk = 35
    defaultConfig { applicationId = "dev.doppel.testapp"; minSdk = 26; targetSdk = 35; versionCode = 18; versionName = "0.1.0-alpha.18" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
