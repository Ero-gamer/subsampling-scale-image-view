import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("maven-publish")
}

group = "com.davemorrissey.labs"
version = "4.3.1"

base {
    archivesName.set("subsampling-scale-image-view-androidx")
}

android {
    compileSdk = 37
    namespace = "com.davemorrissey.labs.subscaleview"

    // This must be INSIDE the android block
    publishing {
        singleVariant("release")
    }

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("proguard-rules.txt")
    }

    // targetSdk on defaultConfig is gone for library modules (AGP 9): set it for tests and lint.
    testOptions {
        targetSdk = 37
    }
    lint {
        targetSdk = 37
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
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                // Lets NDK r27+ produce 16 KB page-size aligned libraries (ignored by older NDKs).
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }
} // This brace correctly closes the android block

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xexplicit-api=warning")
    }
}

val javadocs by configurations.creating

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("androidx.customview:customview:1.2.0")
    javadocs("androidx.annotation:annotation:1.11.0")
    javadocs("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
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

// Cleaned up Javadoc task to prevent "Unexpected input" errors.
// AGP 9 new DSL: no android.bootClasspath / sourceSets srcDirs here, so use the plain source tree
// and the SDK boot classpath from androidComponents.
tasks.register<Javadoc>("javadoc") {
    isFailOnError = false
    source = fileTree("src/main/java")
    classpath += files(androidComponents.sdkComponents.bootClasspath)
}
