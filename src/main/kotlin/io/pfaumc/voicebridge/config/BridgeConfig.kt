package io.pfaumc.voicebridge.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

data class BridgeConfig(
    val enabled: Boolean = true,
    val debug: Boolean = false,
    val defaultDistance: Double = 48.0,
    val whisperMultiplier: Double = 0.33,
    val maxDistance: Double = 128.0,
    val passthrough: Boolean = true,
    val forceTranscode: Boolean = false,
    val worldOverrides: Map<String, Double> = emptyMap()
) {
    companion object {
        private val logger = Logger.getLogger("VoiceBridge")

        fun load(dataFolder: File): BridgeConfig {
            val configFile = File(dataFolder, "config.yml")

            if (!configFile.exists()) {
                dataFolder.mkdirs()
                configFile.writeText(DEFAULT_CONFIG)
                logger.info("Created default config at ${configFile.absolutePath}")
            }

            val yaml = YamlConfiguration.loadConfiguration(configFile)

            val worldOverrides = mutableMapOf<String, Double>()
            val worldsSection = yaml.getConfigurationSection("worlds")
            worldsSection?.getKeys(false)?.forEach { worldName ->
                val distance = worldsSection.getDouble("$worldName.default-distance", -1.0)
                if (distance > 0) {
                    worldOverrides[worldName] = distance
                }
            }

            return BridgeConfig(
                enabled = yaml.getBoolean("bridge.enabled", true),
                debug = yaml.getBoolean("bridge.debug", false),
                defaultDistance = yaml.getDouble("proximity.default-distance", 48.0),
                whisperMultiplier = yaml.getDouble("proximity.whisper-multiplier", 0.33),
                maxDistance = yaml.getDouble("proximity.max-distance", 128.0),
                passthrough = yaml.getBoolean("audio.passthrough", true),
                forceTranscode = yaml.getBoolean("audio.force-transcode", false),
                worldOverrides = worldOverrides
            )
        }

        private val DEFAULT_CONFIG = """
            # Voice Bridge Configuration
            bridge:
              enabled: true
              debug: false

            proximity:
              # Default voice distance in blocks
              default-distance: 48.0
              # Whisper distance = normal distance * this multiplier
              whisper-multiplier: 0.33
              # Maximum allowed voice distance
              max-distance: 128.0

            audio:
              # Pass Opus frames through without re-encoding (recommended, lowest latency)
              passthrough: true
              # Force decode and re-encode all frames (higher CPU, use only if codec mismatch)
              force-transcode: false

            # Per-world distance overrides (optional)
            # worlds:
            #   world_nether:
            #     default-distance: 24.0
            #   world_the_end:
            #     default-distance: 64.0
        """.trimIndent()
    }
}
