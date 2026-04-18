// Top-level build file. Plugin declarations only — no configuration here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.hilt)               apply false
    alias(libs.plugins.ksp)                apply false
    alias(libs.plugins.google.services)    apply false
}
