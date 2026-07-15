plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val testCodecFixScript by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs host-side codecfix.sh transaction and rollback tests."
    workingDir(rootDir)
    commandLine("sh", "hevc/tests/codecfix_test.sh")
}

val validateHevcAssets by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates HEVC XML, JSONC, manifests, and profile invariants."
    workingDir(rootDir)
    commandLine("python3", "hevc/tests/validate_assets.py")
}

tasks.register("verify") {
    group = "verification"
    description = "Runs JVM tests, Android lint, and host-side HEVC checks."
    dependsOn(":app:testDebugUnitTest", ":app:lintDebug", testCodecFixScript, validateHevcAssets)
}
