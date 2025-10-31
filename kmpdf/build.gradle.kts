plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "io.github.bigboiapps"
version = "1.0.0"

kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
    }

    jvm("desktop")

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
            implementation(libs.qrose)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidx.core)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
            }
        }
    }
}

android {
    namespace = "io.github.bigboiapps.kmpdf"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.bigboiapps"
            artifactId = "kmpdf"
            version = "1.0.0"

            pom {
                name.set("KmPDF")
                description.set("Kotlin Multiplatform library for generating PDFs from Compose UI with QR code support")
                url.set("https://github.com/big-jared/kmpdf")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("bigboiapps")
                        name.set("BigBoi Apps")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/big-jared/kmpdf.git")
                    developerConnection.set("scm:git:ssh://github.com/big-jared/kmpdf.git")
                    url.set("https://github.com/big-jared/kmpdf")
                }
            }
        }
    }
}
