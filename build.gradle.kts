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
        classpath("com.android.tools.build:gradle:9.5.2")
        // Built-in Kotlin (AGP 9): this only pins the KGP version AGP uses (no kotlin-android plugin applied).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
