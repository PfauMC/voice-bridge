package io.pfaumc.voicebridge.adapter

import io.pfaumc.voicebridge.VoiceBridgePlugin
import io.pfaumc.voicebridge.session.ModType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.capture.ServerActivation
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.api.server.audio.source.ServerPlayerSource
import su.plo.voice.api.server.event.connection.UdpClientConnectEvent
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.api.server.player.VoiceServerPlayer
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Adapter for Plasmo Voice.
 *
 * Uses PV's addon system:
 * 1. Annotated with @Addon for PV to recognize it
 * 2. Implements AddonInitializer for lifecycle callbacks
 * 3. Uses @InjectPlasmoVoice for DI of the PlasmoVoiceServer instance
 * 4. Loaded via PlasmoVoiceServer.getAddonsLoader().load(this) from the main plugin
 *
 * Audio interception:
 * - Registers a listener on the "proximity" ServerActivation to intercept PV player audio
 * - Creates ServerPlayerSources to relay SVC player audio to PV clients
 */
@Addon(
    id = "voice-bridge",
    name = "Voice Bridge",
    version = "0.1.0",
    authors = ["VoiceBridge"]
)
class PvAdapter(private val plugin: VoiceBridgePlugin) : AddonInitializer {

    private val logger = Logger.getLogger("VoiceBridge-PV")

    // Injected by PV's addon loading system via @InjectPlasmoVoice
    @InjectPlasmoVoice
    lateinit var voiceServer: PlasmoVoiceServer

    private var sourceLine: ServerSourceLine? = null
    private var proximityActivation: ServerActivation? = null

    // ServerPlayerSources for relaying SVC player audio to PV clients.
    // Key: SVC player UUID (the "speaker"), Value: source that PV clients listen to.
    private val outboundSources = ConcurrentHashMap<UUID, ServerPlayerSource>()

    // Track sequence numbers per source for outbound audio
    private val sequenceNumbers = ConcurrentHashMap<UUID, Long>()

    init {
        // Register this addon with PV's addon loader.
        // PV will discover the @Addon annotation, inject @InjectPlasmoVoice fields,
        // and call onAddonInitialize().
        try {
            PlasmoVoiceServer.getAddonsLoader().load(this)
            logger.info("Registered VoiceBridge as PV addon via AddonsLoader")
        } catch (e: Exception) {
            logger.warning("Failed to register with PV AddonsLoader: ${e.message}")
        }
    }

    override fun onAddonInitialize() {
        logger.info("PV addon initializing — PlasmoVoiceServer injected")

        // Register event listeners for player connect/disconnect
        voiceServer.eventBus.register(this, this)

        // Get the proximity source line for creating player sources
        sourceLine = voiceServer.sourceLineManager.getLineByName("proximity").orElse(null)
        if (sourceLine == null) {
            logger.warning("Could not find 'proximity' source line — PV bridge may not work correctly")
        }

        // Hook into the proximity activation to intercept PV player audio
        setupProximityActivationListener()
    }

    override fun onAddonShutdown() {
        shutdown()
    }

    /**
     * Register a listener on PV's proximity activation to intercept audio
     * from PV players and relay it to SVC players.
     */
    private fun setupProximityActivationListener() {
        val activation = voiceServer.activationManager
            .getActivationByName("proximity")
            .orElse(null)

        if (activation == null) {
            logger.warning("Proximity activation not found — will retry on first audio event")
            return
        }

        proximityActivation = activation

        // Register audio interception callback.
        // Return IGNORED so PV still processes the audio normally for PV clients.
        // We just additionally relay it to SVC clients.
        activation.onPlayerActivation { player, packet ->
            onPvPlayerAudio(player, packet)
            ServerActivation.Result.IGNORED
        }

        activation.onPlayerActivationEnd { player, packet ->
            onPvPlayerAudioEnd(player, packet)
            ServerActivation.Result.IGNORED
        }

        logger.info("Proximity activation listener registered")
    }

    // --- Event Handlers ---

    @EventSubscribe
    fun onPlayerConnected(event: UdpClientConnectEvent) {
        val player = event.connection.player
        val playerUuid = player.instance.uuid
        val playerName = player.instance.name

        // Only register if not already registered as SVC
        if (!plugin.sessionManager.isRegistered(playerUuid)) {
            plugin.sessionManager.register(playerUuid, playerName, ModType.PLASMO_VOICE)
            logger.info("PV player connected: $playerName")
        }
    }

    @EventSubscribe
    fun onPlayerDisconnected(event: UdpClientDisconnectedEvent) {
        val player = event.connection.player
        val playerUuid = player.instance.uuid

        // Only remove if this was a PV session
        val session = plugin.sessionManager.getSession(playerUuid)
        if (session?.modType == ModType.PLASMO_VOICE) {
            plugin.sessionManager.unregister(playerUuid)
        }

        // Clean up outbound sources
        outboundSources.remove(playerUuid)?.remove()
        sequenceNumbers.remove(playerUuid)
    }

    // --- Audio Reception from PV Players ---

    private fun onPvPlayerAudio(player: VoicePlayer, packet: PlayerAudioPacket) {
        val playerUuid = player.instance.uuid
        val opusData = packet.data
        val distance = packet.distance
        val sequenceNumber = packet.sequenceNumber

        // Touch session
        plugin.sessionManager.getSession(playerUuid)?.touch()

        // Get the Bukkit player
        val bukkitPlayer = Bukkit.getPlayer(playerUuid) ?: return

        // Relay to SVC players via AudioRelay
        plugin.audioRelay.relayPvToSvc(
            senderUuid = playerUuid,
            senderPlayer = bukkitPlayer,
            opusData = opusData,
            sequenceNumber = sequenceNumber,
            distance = distance
        )
    }

    private fun onPvPlayerAudioEnd(player: VoicePlayer, packet: PlayerAudioEndPacket) {
        val playerUuid = player.instance.uuid

        // Notify SVC adapter to flush the channel for this player
        plugin.audioRelay.svcAdapter?.flushChannel(playerUuid)
    }

    // --- Outbound: Send audio FROM an SVC player TO PV clients ---

    /**
     * Send audio from an SVC player to nearby PV clients using ServerPlayerSource.
     *
     * @return true if audio was sent successfully
     */
    fun sendAudioFromExternalPlayer(
        senderUuid: UUID,
        senderPlayer: Player,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Short
    ): Boolean {
        val line = sourceLine ?: return false

        // Try to get the PV player object for this sender.
        // If the player only has SVC (not PV), they won't be in PV's player manager.
        val pvPlayer = voiceServer.playerManager.getPlayerById(senderUuid)
            .orElse(null)

        if (pvPlayer == null) {
            // Player not known to PV — we cannot create a ServerPlayerSource for them.
            // This is expected for SVC-only players.
            // TODO: Consider using ServerEntitySource or ServerStaticSource as alternative.
            if (plugin.bridgeConfig.debug) {
                logger.fine("Cannot relay to PV: player $senderUuid not in PV player manager")
            }
            return false
        }

        val source = outboundSources.computeIfAbsent(senderUuid) { _ ->
            line.createPlayerSource(pvPlayer, false)
        }

        val seq = sequenceNumbers.compute(senderUuid) { _, current ->
            if (sequenceNumber > 0) sequenceNumber else (current ?: 0) + 1
        } ?: 0L

        return source.sendAudioFrame(opusData, seq, distance)
    }

    /**
     * Signal end of audio stream for a player.
     */
    fun sendAudioEnd(senderUuid: UUID, distance: Short) {
        val seq = sequenceNumbers[senderUuid] ?: return
        outboundSources[senderUuid]?.sendAudioEnd(seq, distance)
    }

    fun shutdown() {
        outboundSources.values.forEach { it.remove() }
        outboundSources.clear()
        sequenceNumbers.clear()
        logger.info("PV adapter shut down")
    }
}
