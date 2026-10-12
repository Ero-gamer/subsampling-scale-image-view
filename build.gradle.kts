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
        // (9.x needs Gradle >= 9.5). AGP 9.0 is a large DSL/variant-API break —
        // e.g. `kotlinOptions{}` and `defaultConfig.targetSdk` are fully removed, not just
        // deprecated — and Kotlin 2.4.x supports AGP 8.5.2+, so 8.13.2 pairs with it fine.
        // 8.13.2 is the latest stable patch of the well-supported 8.x line, so it's the safer
        // "latest safe/compatible" pick until this module's build script is fully migrated
        // and can be verified against AGP 9.x.
        classpath("com.android.tools.build:gradle:8.13.2")
        // Kept in sync with the kotlin-stdlib version declared in library/build.gradle.kts.
        // Kotlin 2.4.x supports AGP 8.5.2+ and Gradle 7.6.3-9.7.0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
