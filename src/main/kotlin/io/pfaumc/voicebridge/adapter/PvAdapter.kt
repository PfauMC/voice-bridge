package io.pfaumc.voicebridge.adapter

import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgePlugin
import io.pfaumc.voicebridge.session.ModType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.encryption.EncryptionException
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.capture.ServerActivation
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.api.server.audio.source.ServerEntitySource
import su.plo.voice.api.server.event.audio.capture.PlayerServerActivationEndEvent
import su.plo.voice.api.server.event.audio.capture.PlayerServerActivationEvent
import su.plo.voice.api.server.event.connection.UdpClientConnectEvent
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent
import su.plo.voice.api.server.player.VoicePlayer
import java.util.*
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
 * - Creates ServerEntitySources to relay SVC player audio to PV clients
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

    var svcAdapter: SvcAdapter? = null

    private var sourceLine: ServerSourceLine? = null
    private var proximityActivation: ServerActivation? = null

    // Re-paces bursty SVC frames into a steady 20ms cadence before sending to PV clients.
    private var repacer: FrameRepacer? = null

    // ServerEntitySources for relaying SVC player audio to PV clients.
    // Key: SVC player UUID (the "speaker"), Value: source that PV clients listen to.
    // Uses entity sources instead of player sources because SVC-only players
    // are not connected to PV's UDP server.
    private val outboundSources = ConcurrentHashMap<UUID, ServerEntitySource>()

    // Track sequence numbers per source for outbound audio
    private val sequenceNumbers = ConcurrentHashMap<UUID, Long>()

    // UUIDs of players with fake bridged UDP connections (SVC-only players).
    // Used to skip re-registering them as real PV sessions in event handlers.
    private val bridgedConnectionUuids = ConcurrentHashMap.newKeySet<UUID>()

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

        // Get the proximity source line for creating player sources
        sourceLine = voiceServer.sourceLineManager.getLineByName("proximity").orElse(null)
        if (sourceLine == null) {
            logger.warning("Could not find 'proximity' source line — PV bridge may not work correctly")
        }

        // Resolve the proximity activation ID for filtering events
        proximityActivation = voiceServer.activationManager
            .getActivationByName("proximity")
            .orElse(null)
        if (proximityActivation == null) {
            logger.warning("Proximity activation not found — PV→SVC bridge may not work")
        }

        val cfg = plugin.bridgeConfig
        if (cfg.repacing) {
            val lead = cfg.repacingJitterFrames.coerceAtLeast(1)
            repacer = FrameRepacer(
                frameIntervalMs = FRAME_INTERVAL_MS,
                leadFrames = lead,
                maxFrames = lead + MAX_EXTRA_FRAMES,
                sink = ::emitFrame
            ).also { it.start() }
            logger.info("SVC->PV frame re-pacing enabled (lead=$lead frames, ${FRAME_INTERVAL_MS}ms cadence)")
        }

        logger.info("PV adapter initialized, listening for activation events")
    }

    override fun onAddonShutdown() {
        shutdown()
    }

    // --- Event Handlers ---

    @EventSubscribe
    fun onPlayerConnected(event: UdpClientConnectEvent) {
        val player = event.connection.player
        val playerUuid = player.instance.uuid

        // Skip if this is our own fake bridged connection for an SVC-only player
        if (playerUuid in bridgedConnectionUuids) return

        val playerName = player.instance.name

        // Register PV mod type — if already registered as SVC, this adds PV as second mod type
        plugin.sessionManager.register(playerUuid, playerName, ModType.PLASMO_VOICE)
        logger.info("PV player connected: $playerName")

        // Mark this player as connected in SVC so SVC clients see a voice icon
        svcAdapter?.setExternalPlayerConnected(playerUuid, true)
    }

    @EventSubscribe
    fun onPlayerDisconnected(event: UdpClientDisconnectedEvent) {
        val player = event.connection.player
        val playerUuid = player.instance.uuid

        // If this is our own fake bridged connection being removed, just clean up the set
        if (bridgedConnectionUuids.remove(playerUuid)) return

        // Mark this player as disconnected in SVC
        svcAdapter?.setExternalPlayerConnected(playerUuid, false)

        // Remove only the PV mod type; session is fully removed only when all mod types are gone
        plugin.sessionManager.unregister(playerUuid, ModType.PLASMO_VOICE)

        // Clean up outbound sources
        outboundSources.remove(playerUuid)?.remove()
        sequenceNumbers.remove(playerUuid)
        repacer?.remove(playerUuid)

        // Clean up outbound SVC channels for this player
        plugin.audioRelay.svcAdapter?.removeChannel(playerUuid)
    }

    // --- Audio Reception from PV Players ---
    // Uses @EventSubscribe on PlayerServerActivationEvent instead of activation.onPlayerActivation
    // because PV's default ProximityServerActivationHelper returns HANDLED, which blocks
    // any subsequently-registered activation listeners from being called.
    // The event bus fires BEFORE the listener loop, so we're guaranteed to receive audio.

    @EventSubscribe
    fun onPlayerActivation(event: PlayerServerActivationEvent) {
        // Only intercept proximity audio
        if (event.activation != proximityActivation) return

        val player = event.player
        val packet = event.packet
        val playerUuid = player.instance.uuid
        val distance = packet.distance
        val sequenceNumber = packet.sequenceNumber

        // PV audio data is encrypted end-to-end (AES/CBC/PKCS5Padding).
        // We must decrypt it before relaying raw Opus to SVC.
        val opusData = try {
            voiceServer.defaultEncryption.decrypt(packet.data)
        } catch (e: EncryptionException) {
            logger.fine("Failed to decrypt PV audio from ${player.instance.name}: ${e.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            return
        }

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

    @EventSubscribe
    fun onPlayerActivationEnd(event: PlayerServerActivationEndEvent) {
        if (event.activation != proximityActivation) return

        val playerUuid = event.player.instance.uuid

        // Notify SVC adapter to flush the channel for this player
        plugin.audioRelay.svcAdapter?.flushChannel(playerUuid)
    }

    // --- Outbound: Send audio FROM an SVC player TO PV clients ---

    /**
     * Send audio from an SVC player to nearby PV clients using ServerEntitySource.
     *
     * Uses entity sources instead of player sources because SVC-only players
     * are not connected to PV's UDP server and cannot use ServerPlayerSource.
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

        outboundSources.computeIfAbsent(senderUuid) { _ ->
            val mcEntity = voiceServer.minecraftServer.getPlayerByInstance(senderPlayer)
            line.createEntitySource(mcEntity, false).apply {
                // Exclude dual-mod players — they already hear SVC audio natively
                addFilter<VoicePlayer> { player -> !plugin.sessionManager.isDualMod(player.instance.uuid) }
            }
        }

        val pacer = repacer
        if (pacer != null) {
            pacer.enqueue(senderUuid, opusData, distance)
        } else {
            emitFrame(senderUuid, opusData, distance)
        }
        return true
    }

    /**
     * Encrypt and send a single Opus frame to PV clients for the given speaker.
     *
     * The sequence number is assigned here (monotonic +1 per source) rather than carried from the
     * caller, so it stays contiguous with frames actually sent — PV's client-side packet-loss
     * compensation requires contiguous sequence numbers. When re-pacing is enabled this runs on the
     * pacer thread; otherwise it runs inline on the caller's thread.
     */
    private fun emitFrame(senderUuid: UUID, opusData: ByteArray, distance: Short) {
        val source = outboundSources[senderUuid] ?: return

        val seq = sequenceNumbers.merge(senderUuid, 1L) { current, _ -> current + 1 } ?: 1L

        // PV uses end-to-end encryption (AES/CBC/PKCS5Padding).
        // Audio from SVC is raw Opus — we must encrypt it before sending
        // so PV clients can decrypt and decode it.
        val encryptedData = try {
            voiceServer.defaultEncryption.encrypt(opusData)
        } catch (e: EncryptionException) {
            logger.fine("Failed to encrypt audio for PV: ${e.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            return
        }

        source.sendAudioFrame(encryptedData, seq, distance)
    }

    /**
     * Signal end of audio stream for a player.
     */
    fun sendAudioEnd(senderUuid: UUID, distance: Short) {
        val seq = sequenceNumbers[senderUuid] ?: return
        outboundSources[senderUuid]?.sendAudioEnd(seq, distance)
    }

    /**
     * Clean up the outbound PV source for a player (e.g., when an SVC player disconnects).
     * Sends audio end signal and removes the source.
     */
    fun cleanupSource(senderUuid: UUID) {
        repacer?.remove(senderUuid)
        val seq = sequenceNumbers.remove(senderUuid) ?: return
        outboundSources.remove(senderUuid)?.let { source ->
            source.sendAudioEnd(seq, 0)
            source.remove()
            logger.fine("Cleaned up PV source for player $senderUuid")
        }
    }

    /**
     * Register a fake UDP connection for an SVC-only player so PV clients see a voice icon.
     */
    fun registerBridgedConnection(playerUuid: UUID) {
        if (!::voiceServer.isInitialized) return

        val bukkitPlayer = Bukkit.getPlayer(playerUuid) ?: return
        val voicePlayer = voiceServer.playerManager.getPlayerByInstance(bukkitPlayer)

        bridgedConnectionUuids.add(playerUuid)
        val connection = BridgedUdpConnection(voicePlayer)
        voiceServer.udpConnectionManager.addConnection(connection)

        // Broadcast player info to all PV clients so they see a voice icon.
        // addConnection() alone only registers internally; the PlayerInfoUpdatePacket
        // must be sent explicitly for clients to update their player list.
        voiceServer.tcpPacketManager.broadcastPlayerInfoUpdate(voicePlayer)
        logger.fine("Registered bridged PV connection for SVC player $playerUuid")
    }

    /**
     * Remove a fake UDP connection for an SVC player who disconnected.
     */
    fun removeBridgedConnection(playerUuid: UUID) {
        if (!::voiceServer.isInitialized) return
        if (!bridgedConnectionUuids.contains(playerUuid)) return

        val secret = voiceServer.udpConnectionManager.getSecretByPlayerId(playerUuid)
        voiceServer.udpConnectionManager.removeConnection(secret)
        // bridgedConnectionUuids is cleaned up in onPlayerDisconnected when the event fires
        logger.fine("Removed bridged PV connection for SVC player $playerUuid")
    }

    fun shutdown() {
        repacer?.shutdown()
        repacer = null

        // Clean up any remaining bridged connections
        for (uuid in bridgedConnectionUuids) {
            try {
                val secret = voiceServer.udpConnectionManager.getSecretByPlayerId(uuid)
                voiceServer.udpConnectionManager.removeConnection(secret)
            } catch (_: Exception) {
            }
        }
        bridgedConnectionUuids.clear()

        outboundSources.values.forEach { it.remove() }
        outboundSources.clear()
        sequenceNumbers.clear()
        logger.info("PV adapter shut down")
    }

    companion object {
        // Both mods use 20ms Opus frames (960 samples @ 48kHz).
        private const val FRAME_INTERVAL_MS = 20L
        // Headroom above the lead before the pacer drops the oldest frame (bounds latency under drift).
        private const val MAX_EXTRA_FRAMES = 10
    }
}
