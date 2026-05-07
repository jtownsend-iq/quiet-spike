// android/quiet-spike/build.gradle.kts (root)
//
// Top-level build file. Plugin versions are pinned in
// gradle/libs.versions.toml; modules apply the alias.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}
