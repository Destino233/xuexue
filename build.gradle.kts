import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Locale

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.8.2"
    kotlin("plugin.serialization") version "2.4.0"
    id("dev.detekt") version "2.0.0-alpha.5"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

dependencyLocking {
    lockAllConfigurations()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

ktlint {
    version.set("1.8.0")
    verbose.set(true)
    outputToConsole.set(true)
    baseline.set(file("config/ktlint/baseline.xml"))
    filter {
        exclude("**/build/**")
    }
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    when (name) {
        "detektMainDesktop" -> baseline.set(file("config/detekt/main-desktop.xml"))
        "detektTestDesktop" -> baseline.set(file("config/detekt/test-desktop.xml"))
    }
}

tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
    when (name) {
        "detektBaselineMainDesktop" -> baseline.set(file("config/detekt/main-desktop.xml"))
        "detektBaselineTestDesktop" -> baseline.set(file("config/detekt/test-desktop.xml"))
    }
}

group = "dev.xuexue"
val productVersion = "0.1.0"
version = "$productVersion-alpha"
val muPdfVersion = "1.27.2"
val hostOsName = System.getProperty("os.name").lowercase(Locale.ROOT)
val hostArchitecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
val muPdfOs =
    when {
        hostOsName.startsWith("linux") -> "linux"
        hostOsName.startsWith("mac") -> "macos"
        hostOsName.startsWith("win") -> "windows"
        else -> error("Unsupported MuPDF host operating system: $hostOsName")
    }
val muPdfArchitecture =
    when (hostArchitecture) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported MuPDF host architecture: $hostArchitecture")
    }
val composeArchitecture = if (muPdfArchitecture == "x86_64") "x64" else "arm64"
val muPdfPlatform = "$muPdfOs-$muPdfArchitecture"
val composePlatform = "$muPdfOs-$composeArchitecture"
val muPdfNativeLibraryName =
    when (muPdfOs) {
        "windows" -> "xuexue_mupdf.dll"
        "macos" -> "libxuexue_mupdf.dylib"
        else -> "libxuexue_mupdf.so"
    }
val muPdfDirectory = layout.projectDirectory.dir(".mupdf/$muPdfVersion/$muPdfPlatform")
val muPdfNativeLibrary = muPdfDirectory.file(muPdfNativeLibraryName)
val muPdfAppResourcesRoot = layout.buildDirectory.dir("generated/mupdf-app-resources")

val bootstrapMuPdf =
    tasks.register<Exec>("bootstrapMuPdf") {
        commandLine(
            "bash",
            layout.projectDirectory
                .file("tools/bootstrap-mupdf-native.sh")
                .asFile.absolutePath,
        )
        inputs.files(
            layout.projectDirectory.file("tools/bootstrap-mupdf-native.sh"),
            layout.projectDirectory.file("tools/native/mupdf_bridge.c"),
        )
        outputs.file(muPdfNativeLibrary)
    }

val prepareMuPdfAppResources =
    tasks.register<Sync>("prepareMuPdfAppResources") {
        dependsOn(bootstrapMuPdf)
        from(muPdfDirectory) {
            include(muPdfNativeLibraryName, "COPYING")
        }
        into(muPdfAppResourcesRoot.map { root -> root.dir(composePlatform) })
    }

tasks.matching { it.name == "compileKotlinDesktop" || it.name == "compileTestKotlinDesktop" }.configureEach {
    dependsOn(bootstrapMuPdf)
}

tasks.withType<Test>().configureEach {
    dependsOn(bootstrapMuPdf)
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.library.path=${muPdfDirectory.asFile.absolutePath}",
    )
    listOf(
        "xuexue.pdf.eval.pdf",
        "xuexue.pdf.eval.manifest",
        "xuexue.pdf.eval.report",
        "xuexue.pdf.eval.endpoint",
        "xuexue.pdf.eval.sidecarPid",
        "xuexue.pdf.eval.runtime",
        "xuexue.pdf.eval.dpi",
        "xuexue.pdf.benchmark.pages",
        "xuexue.pdf.benchmark.report",
    ).forEach { propertyName ->
        System.getProperty(propertyName)?.let { propertyValue ->
            systemProperty(propertyName, propertyValue)
        }
    }
}

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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        getByName("desktopMain") {
            dependencies {
                implementation(project(":pi-coding-agent"))
                implementation(project(":pi-agent-core"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.xuexue.MainKt"
        dependsOn("bootstrapMuPdf", "prepareMuPdfAppResources")
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
        )

        nativeDistributions {
            modules("java.net.http")
            appResourcesRootDir.set(muPdfAppResourcesRoot)
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "XueXue"
            packageVersion = productVersion
            description = "Local-first reading, notes, and agent workspace"
            vendor = "XueXue"
            macOS {
                // Apple package versions must start with a positive major number.
                packageVersion = "1"
                packageBuildVersion = "1"
            }
        }
    }
}

tasks.matching { it.name == "run" }.configureEach {
    if (this is JavaExec) jvmArgs("-Djava.library.path=${muPdfDirectory.asFile.absolutePath}")
}
