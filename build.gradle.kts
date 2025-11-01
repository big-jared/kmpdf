plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/config/detekt/detekt.yml")
    baseline = file("$projectDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(true)
        sarif.required.set(true)
    }
}

// Binary Compatibility Validator
apiValidation {
    ignoredProjects.addAll(listOf("sample"))
    nonPublicMarkers.addAll(listOf("io.github.bigboyapps.kmpdf.internal.InternalKmPdfApi"))
}

// Dokka configuration
subprojects {
    plugins.withId("org.jetbrains.dokka") {
        tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
            dokkaSourceSets.configureEach {
                includes.from("README.md")
                jdkVersion.set(11)

                sourceLink {
                    localDirectory.set(projectDir.resolve("src"))
                    remoteUrl.set(uri("https://github.com/big-jared/kmpdf/tree/main/${project.name}/src").toURL())
                    remoteLineSuffix.set("#L")
                }
            }
        }
    }
}
