import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    id("com.vanniktech.maven.publish") version "0.28.0"
}

val libraryVersion = "1.2.0"

// Generates KMPDF_VERSION from libraryVersion, so the version written into PDFs matches each release
val generateVersionSource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/kmpdfVersion/kotlin")
    val version = libraryVersion
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("io/github/bigboyapps/kmpdf/KmPdfVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package io.github.bigboyapps.kmpdf\n\n" +
                "/** The KmPDF version, written into generated PDFs as their producer. */\n" +
                "internal const val KMPDF_VERSION = \"$version\"\n"
        )
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.big-jared",
        artifactId = "kmpdf",
        version = libraryVersion
    )

    pom {
        name.set("KmPDF")
        description.set("Kotlin Multiplatform library for generating PDFs from Compose UI")
        inceptionYear.set("2025")
        url.set("https://github.com/big-jared/kmpdf")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("big-jared")
                name.set("Jared Guttromson")
                email.set("jaredguttromson@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/big-jared/kmpdf")
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}

kotlin {
    jvmToolchain(17)

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // Targets that render with Skia through ImageComposeScene
            group("skiko") {
                withJvm()
                group("ios") {
                    withIosX64()
                    withIosArm64()
                    withIosSimulatorArm64()
                }
                withWasmJs()
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release")

        // Run the shared commonTest suites as instrumented tests on a device or emulator
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }

    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "KmPdf"
            isStatic = true
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateVersionSource)
        }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core)
        }

        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.activityCompose)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
            implementation(libs.pdfbox)
            implementation(libs.kotlinx.coroutines.swing)
        }

        jvmTest.dependencies {
            // Skia native runtime, needed to render pages in tests
            implementation(compose.desktop.currentOs)
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

android {
    namespace = "io.github.bigboyapps.kmpdf"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
