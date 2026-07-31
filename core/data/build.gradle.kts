plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.scamshield.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// architecture.md §10.1: ":core:data is the only module that may declare a network
// dependency." Rule-pack parsing, the Room history database, preferences, and the opt-in
// RDAP lookup all live here precisely so that the boundary is one module wide and testable.
dependencies {
    api(projects.core.model)
    // Both are plain JVM modules with pure, already-tested parsing logic that rule-pack
    // loading needs: PublicSuffixListParser (PSL algorithm) and BloomDomainReputationIndex
    // (reputation.bin's Bloom filter). Their own doc comments name `:core:data` as the owner
    // of asset I/O and fallback decisions around them -- reusing them here instead of
    // re-implementing either (both are correctness-subtle: see HANDOFF's own account of the
    // Bloom filter's BigInteger overflow bug) is safer than a second hand-rolled copy.
    implementation(projects.core.analysis)
    implementation(projects.analyzer.url)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.room.testing)
}
