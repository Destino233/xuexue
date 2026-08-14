pluginManagement {
    repositories {
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            gradlePluginPortal()
            google()
            mavenCentral()
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            gradlePluginPortal()
            google()
            mavenCentral()
        }
    }
}

dependencyResolutionManagement {
    repositories {
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            google()
            mavenCentral()
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "xuexue"

include(":pi-ai")
include(":pi-agent-core")
include(":pi-coding-agent")
