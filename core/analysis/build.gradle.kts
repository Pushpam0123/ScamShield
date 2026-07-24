import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// architecture.md §5: ":core:analysis depends on the analyzer *interfaces* only, never their
// implementations." The interfaces live in :core:model (DECISIONS.md D-002), so this module's
// only project dependency is :core:model — and it is a plain JVM library, which makes an
// accidental dependency on an analyzer implementation a compile error rather than a review note.
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
