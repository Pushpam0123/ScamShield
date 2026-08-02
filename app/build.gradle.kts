plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.scamshield.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.scamshield.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // architecture.md §10.4 / C5 — the 7 shipped languages. Anything not listed here
        // is stripped from the APK, which also keeps the size budget honest (G5).
        resourceConfigurations += listOf("en", "hi", "bn", "ta", "te", "mr")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE*",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/**
 * G5: the APK must stay under 25 MB.
 *
 * This measures the on-disk size of the release APK, which is an upper bound on Play's
 * download size — Play serves a compressed, device-targeted split, so the real number is
 * smaller. Reporting the pessimistic figure keeps the budget honest, and reporting it on
 * every CI run means a regression shows up in the change that caused it.
 */
val apkSizeBudgetBytes = 25L * 1024 * 1024

val checkApkSize = tasks.register("checkApkSize") {
    group = "verification"
    description = "Fails if the release APK exceeds the 25 MB budget (architecture.md G5)"
    dependsOn("assembleRelease")

    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val budget = apkSizeBudgetBytes

    doLast {
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        if (apks.isEmpty()) throw GradleException("No release APK found in ${apkDir.get().asFile}")

        apks.forEach { apk ->
            val mb = apk.length() / 1024.0 / 1024.0
            logger.lifecycle("APK ${apk.name}: %.2f MB (budget %.0f MB)".format(mb, budget / 1024.0 / 1024.0))
            if (apk.length() > budget) {
                throw GradleException(
                    "${apk.name} is %.2f MB, over the %.0f MB budget of architecture.md G5."
                        .format(mb, budget / 1024.0 / 1024.0),
                )
            }
        }
    }
}

// The rule pack is generated output (root build.gradle.kts's `buildRulepack` task) and is
// gitignored, not committed — a fresh checkout has no app/src/main/assets/rulepack until this
// runs once. Wiring it into `preBuild` means an ordinary `./gradlew build` regenerates it
// automatically instead of silently packaging a stale or missing pack.
tasks.named("preBuild") {
    dependsOn(rootProject.tasks.named("buildRulepack"))
}

// The ML model assets (Phase 4) are gitignored generated output too — but unlike the rule pack,
// they are NOT regenerable from the ordinary build: producing them needs the Python `ml/`
// pipeline, torch, and a dataset (`cd ml && make teacher distill prune export calibrate`). So
// this is a standalone copy task, deliberately NOT wired into `preBuild`: a fresh checkout that
// hasn't trained a model simply has no `assets/model/`, and `ClassifierAnalyzer` degrades to
// `Signal.Unavailable` exactly as architecture.md C6 requires (the app stays fully functional on
// rules alone). Run `./gradlew :app:copyModelAssets` after training to bundle a model.
val mlArtifactsDir = rootProject.layout.projectDirectory.dir("ml/artifacts").asFile
val quantizedModelFile = mlArtifactsDir.resolve("model.int8.onnx")
tasks.register<Copy>("copyModelAssets") {
    group = "build setup"
    description = "Copies the trained ONNX model, tokenizer, and calibration meta into app assets (Phase 4)"
    // A dev-only, run-on-demand copy that isn't part of the cached build graph (it's never a
    // dependency of assemble/test). Marking it exempt is cleaner than contorting a Copy spec to
    // satisfy the configuration cache for a task that gains nothing from being cached.
    notCompatibleWithConfigurationCache("Standalone on-demand asset copy, not part of the build graph")
    into(layout.projectDirectory.dir("src/main/assets/model"))
    // String regex form of rename (not a closure) keeps this task configuration-cache-safe.
    from(quantizedModelFile) { rename("model\\.int8\\.onnx", "model.onnx") }
    from(mlArtifactsDir.resolve("tokenizer.json"))
    from(mlArtifactsDir.resolve("meta.json"))
    // Don't fail the whole build when a model hasn't been trained yet — just copy nothing.
    onlyIf { quantizedModelFile.exists() }
}

dependencies {
    // architecture.md §5 — :app is the only module that may see analyzer *implementations*.
    // It binds them to interfaces via Hilt so that :core:analysis stays implementation-blind.
    implementation(projects.core.model)
    implementation(projects.core.analysis)
    implementation(projects.core.explain)
    implementation(projects.core.data)
    implementation(projects.analyzer.url)
    implementation(projects.analyzer.sender)
    implementation(projects.analyzer.pattern)
    // Phase 4: the classifier analyzer. Bundled here so Hilt (AnalysisModule) can bind it; the
    // module degrades to Signal.Unavailable when no model asset is present (architecture.md C6).
    implementation(projects.analyzer.classifier)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // Phase 1 fixture corpus (design.md section 12): decodes app/src/test/resources/fixtures/verdicts.json.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
