plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.doppel.developer"; compileSdk = 35
    defaultConfig { applicationId = "dev.doppel.developer"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.1.0-alpha.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":sdk")) }
