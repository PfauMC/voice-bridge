rootProject.name = "paper-plugin-template"

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven {
            name = "Canvas"
            url = uri("https://maven.canvasmc.io/snapshots")
        }
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            plugin("shadow", "io.github.goooler.shadow").version("8.1.8")
            plugin("paper", "io.papermc.paperweight.userdev").version("2.0.0-beta.19")
            plugin("runpaper", "xyz.jpenilla.run-paper").version("3.0.2")
        }
    }
}
