import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// architecture.md §5: ":core:model depends on nothing."
//
// This module is deliberately a plain Kotlin JVM library, not an Android library.
// The build simply cannot express an Android or network dependency here, which is
// how the privacy architecture (§10.1) is enforced structurally rather than by
// convention. The dependency-check task in :app verifies this claim in CI.
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // value classes and exhaustive-when warnings should be errors in the domain layer
        allWarningsAsErrors.set(false)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
