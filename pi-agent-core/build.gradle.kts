plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "dev.xuexue"
version = rootProject.version

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

ktlint {
    version.set("1.8.0")
    verbose.set(true)
    outputToConsole.set(true)
    baseline.set(rootProject.file("config/ktlint/baseline.xml"))
}

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":pi-ai"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
