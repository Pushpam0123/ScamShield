plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.scamshield.analyzer.classifier"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get() // only so android.ndkDirectory resolves for vendorNativeStl
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    // DJL's libdjl_tokenizer.so dlopens libc++_shared.so, which neither it nor onnxruntime-android
    // ships; we vendor it into src/main/jniLibs (see vendorNativeStl). pickFirst guards the merge if
    // a future native dep also bundles it.
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // The C6 degrade path logs via android.util.Log; let JVM unit tests see stubbed defaults
    // rather than the "not mocked" RuntimeException. The real inference path is instrumented.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// The instrumented parity test (design.md §9.5) needs the bundled model/tokenizer/meta + the
// Python reference fixture, all gitignored `ml/` output. On-demand like `:app:copyModelAssets` (not
// wired into the graph, so a checkout without a model still compiles). Regenerate with
// `cd ml && make export parity-fixture`, then `:analyzer:classifier:copyModelTestAssets`.
val mlArtifactsDir = rootProject.layout.projectDirectory.dir("ml/artifacts").asFile
val quantizedModelFile = mlArtifactsDir.resolve("model.int8.onnx")
tasks.register<Copy>("copyModelTestAssets") {
    group = "build setup"
    description = "Copies the ONNX model, tokenizer, meta, and parity fixture into androidTest assets"
    notCompatibleWithConfigurationCache("Standalone on-demand asset copy, not part of the build graph")
    into(layout.projectDirectory.dir("src/androidTest/assets/model"))
    from(quantizedModelFile) { rename("model\\.int8\\.onnx", "model.onnx") }
    from(mlArtifactsDir.resolve("tokenizer.json"))
    from(mlArtifactsDir.resolve("meta.json"))
    from(mlArtifactsDir.resolve("parity_fixture.json"))
    onlyIf { quantizedModelFile.exists() }
}

// Copy libc++_shared.so for every ABI the native deps ship out of the configured NDK into
// src/main/jniLibs (see the packaging note above). On-demand and gitignored like the model assets;
// run `:analyzer:classifier:vendorNativeStl` with an installed NDK.
val stlAbis = listOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "arm-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android",
)
tasks.register("vendorNativeStl") {
    group = "build setup"
    description = "Copies libc++_shared.so out of the NDK into src/main/jniLibs (DJL tokenizer needs it)"
    notCompatibleWithConfigurationCache("Reads the resolved NDK dir; standalone on-demand copy")
    doLast {
        val ndkDir = android.ndkDirectory
        val sysrootLib = ndkDir.resolve("toolchains/llvm/prebuilt")
            .listFiles()?.firstOrNull()?.resolve("sysroot/usr/lib")
            ?: error("no NDK prebuilt toolchain under $ndkDir — install one with sdkmanager 'ndk;<version>'")
        for ((abi, triple) in stlAbis) {
            val src = sysrootLib.resolve("$triple/libc++_shared.so")
            require(src.exists()) { "missing $src" }
            val destDir = layout.projectDirectory.dir("src/main/jniLibs/$abi").asFile
            destDir.mkdirs()
            src.copyTo(destDir.resolve("libc++_shared.so"), overwrite = true)
        }
        logger.lifecycle("vendored libc++_shared.so for ${stlAbis.size} ABIs from $ndkDir")
    }
}

// The one analyzer that must be an Android library: ONNX Runtime Mobile ships native
// libraries and the tokenizer bindings load from assets. architecture.md C6 means nothing
// else may depend on this module being present or working.
dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.tokenizers)
    implementation(libs.tokenizers.native.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlinx.serialization.json)
}
