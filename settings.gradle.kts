rootProject.name = "voice-bridge"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.canvasmc.io/snapshots")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            plugin("paper", "io.papermc.paperweight.userdev").version("2.0.0-beta.19")
            plugin("runpaper", "xyz.jpenilla.run-paper").version("3.0.2")
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").version("2.3.0")
        }
    }
}
