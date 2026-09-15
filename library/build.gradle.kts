import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("maven-publish")
}

group = "com.davemorrissey.labs"
version = "4.3.1"

base {
    archivesName.set("subsampling-scale-image-view-androidx")
}

android {
    compileSdk = 35
    namespace = "com.davemorrissey.labs.subscaleview"

    // This must be INSIDE the android block
    publishing {
        singleVariant("release")
    }

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("proguard-rules.txt")
    }

    // targetSdk on defaultConfig is deprecated for library modules (removed in AGP 9.0) —
    // it only ever affected the test APK and lint's target, so those are now set explicitly.
    testOptions {
        targetSdk = 35
    }
    lint {
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                // arm64-v8a: modern 64-bit Android (NEON SIMD enabled)
                // armeabi-v7a: 32-bit ARM fallback
                // x86_64: emulator support
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }
} // This brace correctly closes the android block

// kotlinOptions{} is deprecated (removed in AGP 9.0) — migrated to the compilerOptions DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xexplicit-api=warning")
    }
}

val javadocs by configurations.creating

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("androidx.customview:customview:1.2.0")
    javadocs("androidx.annotation:annotation:1.10.0")
    javadocs("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.Ero-gamer"
                artifactId = "subsampling-scale-image-view-androidx"
                version = "4.3.1"
            }
        }
    }
}

// Cleaned up Javadoc task to prevent "Unexpected input" errors
tasks.register<Javadoc>("javadoc") {
    isFailOnError = false
    // AndroidSourceDirectorySet.java already IS a FileTree (it implements SourceDirectorySet,
    // which extends FileTree) — `.sourceFiles` was never a valid member here and is the root
    // cause of this build's failure ("Unresolved reference: sourceFiles").
    source = android.sourceSets.getByName("main").java
    classpath += project.files(android.bootClasspath.joinToString(File.pathSeparator))
}
