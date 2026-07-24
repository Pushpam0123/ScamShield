import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// architecture.md §5: "No :analyzer:* module may depend on another :analyzer:* module."
// A rule analyzer needs nothing but the domain types and the parsed rule pack, both of
// which live in :core:model. Keeping it a plain JVM library also means it cannot declare
// a network dependency (§10.1) and can be unit-tested with no Android runtime at all.
dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
