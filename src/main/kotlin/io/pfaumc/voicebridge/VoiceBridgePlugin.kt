package io.pfaumc.voicebridge

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.pfaumc.voicebridge.adapter.PvAdapter
import io.pfaumc.voicebridge.adapter.SvcAdapter
import io.pfaumc.voicebridge.command.VoiceBridgeCommand
import io.pfaumc.voicebridge.config.BridgeConfig
import io.pfaumc.voicebridge.relay.AudioRelay
import io.pfaumc.voicebridge.session.SessionManager
import io.pfaumc.voicebridge.spatial.SpatialMapper
import kotlinx.coroutines.*
import org.bukkit.plugin.java.JavaPlugin
import kotlin.time.Duration.Companion.seconds

class VoiceBridgePlugin : JavaPlugin() {

    lateinit var bridgeConfig: BridgeConfig
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var spatialMapper: SpatialMapper
        private set
    lateinit var audioRelay: AudioRelay
        private set

    private var svcAdapter: SvcAdapter? = null
    private var pvAdapter: PvAdapter? = null

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("VoiceBridge")
    )

    override fun onEnable() {
        instance = this

        // Load config
        bridgeConfig = BridgeConfig.load(dataFolder)
        if (!bridgeConfig.enabled) {
            logger.info("Voice Bridge is disabled in config")
            return
        }

        // Initialize core components
        sessionManager = SessionManager()
        spatialMapper = SpatialMapper(bridgeConfig)
        audioRelay = AudioRelay(sessionManager, spatialMapper, bridgeConfig)

        // Initialize adapters
        val svcAvailable = initSvcAdapter()
        val pvAvailable = initPvAdapter()

        if (!svcAvailable && !pvAvailable) {
            logger.warning("Neither Simple Voice Chat nor Plasmo Voice detected. Bridge has nothing to do.")
            return
        }
        if (!svcAvailable) {
            logger.warning("Simple Voice Chat not detected. Bridge will not function without both mods.")
            return
        }
        if (!pvAvailable) {
            logger.warning("Plasmo Voice not detected. Bridge will not function without both mods.")
            return
        }

        // Wire adapters to relay
        audioRelay.svcAdapter = svcAdapter
        audioRelay.pvAdapter = pvAdapter

        // Wire cross-adapter references for connection icon bridging
        svcAdapter?.pvAdapter = pvAdapter
        pvAdapter?.svcAdapter = svcAdapter

        // Start cleanup coroutine
        scope.launch {
            while (isActive) {
                delay(5.seconds)
                sessionManager.cleanup()
            }
        }

        // Register commands via Brigadier
        registerCommands()

        logger.info("Voice Bridge enabled — bridging Simple Voice Chat <-> Plasmo Voice")
        BridgeMetrics.log(logger)
    }

    override fun onDisable() {
        svcAdapter?.shutdown()
        pvAdapter?.shutdown()
        sessionManager.clear()
        scope.cancel()
        logger.info("Voice Bridge disabled")
    }

    fun reloadBridgeConfig() {
        bridgeConfig = BridgeConfig.load(dataFolder)
        spatialMapper.config = bridgeConfig
        logger.info("Voice Bridge config reloaded")
    }

    @Suppress("UnstableApiUsage")
    private fun registerCommands() {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                VoiceBridgeCommand.buildCommand(this),
                "Voice Bridge admin commands",
                listOf("vb")
            )
        }
    }

    private fun initSvcAdapter(): Boolean {
        return try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin")
            svcAdapter = SvcAdapter(this)
            logger.info("Simple Voice Chat detected — SVC adapter initialized")
            true
        } catch (e: ClassNotFoundException) {
            logger.info("Simple Voice Chat not found on classpath")
            false
        }
    }

    private fun initPvAdapter(): Boolean {
        return try {
            Class.forName("su.plo.voice.api.server.PlasmoVoiceServer")
            pvAdapter = PvAdapter(this)
            logger.info("Plasmo Voice detected — PV adapter initialized")
            true
        } catch (e: ClassNotFoundException) {
            logger.info("Plasmo Voice not found on classpath")
            false
        }
    }

    companion object {
        lateinit var instance: VoiceBridgePlugin
            private set
    }
}
