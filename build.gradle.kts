plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// Rule pack build (implementation.md Phase 1.2)
//
// Rules are data, not code (architecture.md §11). The authored JSON in rulepack/src
// is schema-validated and compiled into app assets by a Python script. The build
// fails on an invalid pack — a partially-loaded pack must never reach a device.
// ---------------------------------------------------------------------------
val buildRulepack by tasks.registering(Exec::class) {
    group = "scamshield"
    description = "Validates rulepack/src and emits app/src/main/assets/rulepack/v1/"
    workingDir = rootDir
    commandLine("python3", "rulepack/build_rulepack.py")
    inputs.dir(layout.projectDirectory.dir("rulepack/src"))
    inputs.dir(layout.projectDirectory.dir("rulepack/schema"))
    inputs.file(layout.projectDirectory.file("rulepack/build_rulepack.py"))
    outputs.dir(layout.projectDirectory.dir("app/src/main/assets/rulepack"))
}
