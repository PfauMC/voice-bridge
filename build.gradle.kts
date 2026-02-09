plugins {
    java
    idea
    alias(libs.plugins.shadow)
    alias(libs.plugins.paper)
    alias(libs.plugins.runpaper)
    alias(libs.plugins.kotlin.jvm)
}

group = project.properties["plugin.group"].toString()
version = project.properties["plugin.version"].toString()

repositories {
    mavenCentral()

    // Simple Voice Chat API
    maven("https://maven.maxhenkel.de/repository/public") {
        name = "henkelmax"
    }

    // Plasmo Voice API
    maven("https://repo.plasmoverse.com/releases") {
        name = "plasmoverse-releases"
    }
    maven("https://repo.plasmoverse.com/snapshots") {
        name = "plasmoverse-snapshots"
    }
}

dependencies {
    paperweight.paperDevBundle(project.properties["paper.version"].toString())

    // Simple Voice Chat API (provided at runtime by the mod)
    compileOnly("de.maxhenkel.voicechat:voicechat-api:${project.properties["svc.api.version"]}")

    // Plasmo Voice Server API (provided at runtime by the mod)
    compileOnly("su.plo.voice.api:server:${project.properties["plasmo.voice.version"]}")
    compileOnly("su.plo.voice.api:common:${project.properties["plasmo.voice.version"]}")
    compileOnly("su.plo.voice.api:server-proxy-common:${project.properties["plasmo.voice.version"]}")
    compileOnly("su.plo.voice:protocol:${project.properties["plasmo.voice.version"]}")
    compileOnly("su.plo.slib:api-server:1.2.0")

    compileOnly(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        val props = mapOf(
            "name" to project.properties["plugin.name"],
            "version" to project.version,
            "main" to project.properties["plugin.main"],
            "apiVersion" to project.properties["paper.api"],
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
