plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.doppel.developer"; compileSdk = 35
    defaultConfig { applicationId = "dev.doppel.developer"; minSdk = 26; targetSdk = 35; versionCode = 64; versionName = "0.1.0-alpha.64-recovery"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    // Opt-in diagnostic build for Android 9 emulator HWUI crashes. The normal
    // developer and release apps retain hardware acceleration.
    defaultConfig.manifestPlaceholders["developerHardwareAccelerated"] =
        (providers.gradleProperty("legacySoftwareRendering").orNull != "true").toString()
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
dependencies { implementation(project(":sdk")); androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation("junit:junit:4.13.2") }
