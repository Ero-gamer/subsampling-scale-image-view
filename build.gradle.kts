// Top-level build file where you can add configuration options common to all sub-projects/modules.
allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        // AGP is pinned to the 8.13.x line deliberately, NOT bumped to the newer 9.x major
        // (9.4.0 is latest stable as of writing). AGP 9.0 is a large DSL/variant-API break —
        // e.g. `kotlinOptions{}` and `defaultConfig.targetSdk` are fully removed, not just
        // deprecated — and Kotlin 2.3.0's own documented compatibility ceiling is AGP 8.13.0.
        // 8.13.2 is the latest stable patch of the well-supported 8.x line, so it's the safer
        // "latest safe/compatible" pick until this module's build script is fully migrated
        // and can be verified against AGP 9.x.
        classpath("com.android.tools.build:gradle:9.4.0")
        // Bumped 2.2.10 -> 2.3.20 to match the kotlin-stdlib version already declared in
        // library/build.gradle.kts (they were out of sync) and is within Kotlin 2.3.0's
        // documented AGP 8.13.0 compatibility ceiling.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}
