plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.doppel.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 26; testInstrumentationRunner = "android.test.InstrumentationTestRunner"; consumerProguardFiles("consumer-rules.pro", "poi-consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ttsAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/asrAssets"))
    androidResources { noCompress += "onnx" }
}
val ttsArchive = rootProject.file("../.tooling/tts/kokoro-int8-multi-lang-v1_1.tar.bz2")
val prepareEmbeddedTts by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("../scripts/prepare-embedded-tts.py"))
    inputs.file(rootProject.file("../docs/licenses/embedded-chinese-tts.txt"))
    inputs.file(ttsArchive)
    outputs.dir(layout.buildDirectory.dir("generated/ttsAssets"))
    val executable = providers.gradleProperty("doppel.python").orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
    commandLine(executable.get(), rootProject.file("../scripts/prepare-embedded-tts.py").absolutePath, "--archive", ttsArchive.absolutePath,
        "--output", layout.buildDirectory.dir("generated/ttsAssets").get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(prepareEmbeddedTts) }
val asrArchive = rootProject.file("../.tooling/asr/paraformer-small.tar.bz2")
val prepareEmbeddedAsr by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("../scripts/prepare-embedded-asr.py"))
    inputs.file(rootProject.file("../docs/licenses/embedded-chinese-asr.txt"))
    inputs.file(asrArchive)
    outputs.dir(layout.buildDirectory.dir("generated/asrAssets"))
    val executable = providers.gradleProperty("doppel.python").orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
    commandLine(executable.get(), rootProject.file("../scripts/prepare-embedded-asr.py").absolutePath,
        "--archive", asrArchive.absolutePath, "--output", layout.buildDirectory.dir("generated/asrAssets").get().asFile.absolutePath)
}
tasks.named("preBuild") { dependsOn(prepareEmbeddedAsr) }
dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(files("libs/poi-android-5.5.1.jar"))
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
