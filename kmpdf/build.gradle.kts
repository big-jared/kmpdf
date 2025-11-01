import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    id("com.vanniktech.maven.publish") version "0.28.0"
}

val libraryVersion = "1.0.0"

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

    androidTarget {
        publishLibraryVariants("release")
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
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidx.core)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
            implementation(libs.pdfbox)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "io.github.bigboyapps.kmpdf"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
