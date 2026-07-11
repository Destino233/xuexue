import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.8.2"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "dev.xuexue"
version = "0.1.0-alpha"

kotlin {
    jvmToolchain(26)

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("org.apache.pdfbox:pdfbox:3.0.4")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.xuexue.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "XueXue"
            packageVersion = "1.0.0"
            description = "Local-first reading, notes, and agent workspace"
            vendor = "XueXue"
        }
    }
}
