// Top-level build file. Plugins are declared here with `apply false` so subprojects
// share a single resolved version.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
}
