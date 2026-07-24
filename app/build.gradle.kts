plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
