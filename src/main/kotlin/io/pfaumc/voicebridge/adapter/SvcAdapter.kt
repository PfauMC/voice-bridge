package io.pfaumc.voicebridge.adapter

import de.maxhenkel.voicechat.api.BukkitVoicechatService
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgePlugin
import io.pfaumc.voicebridge.session.ModType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Adapter for Simple Voice Chat.
 *
 * Implements VoicechatPlugin to hook into SVC's server-side API.
 * - Listens for MicrophonePacketEvents from SVC players and relays to PV players via AudioRelay.
 * - Creates EntityAudioChannels to relay audio FROM PV players TO SVC players.
 *
 * Registration: This class must be registered as a VoicechatPlugin via SVC's service discovery.
 * On Paper/Bukkit, this is done via BukkitVoicechatService.
 */
class SvcAdapter(private val plugin: VoiceBridgePlugin) : VoicechatPlugin {

    private val logger = Logger.getLogger("VoiceBridge-SVC")

    private var serverApi: VoicechatServerApi? = null

    // EntityAudioChannels for relaying PV player audio to SVC clients.
    // Key: PV player UUID (the "speaker"), Value: channel that SVC clients listen to.
    private val outboundChannels = ConcurrentHashMap<UUID, EntityAudioChannel>()

    init {
        // Register this plugin with SVC's Bukkit service
        registerWithSvc()
    }

    private fun registerWithSvc() {
        val service = Bukkit.getServicesManager()
            .load(BukkitVoicechatService::class.java)
        if (service != null) {
            service.registerPlugin(this)
            logger.info("Registered VoiceBridge as SVC plugin")
        } else {
            logger.warning("BukkitVoicechatService not available — SVC may not be fully loaded yet")
        }
    }

    override fun getPluginId(): String = "voice-bridge"

    override fun initialize(api: VoicechatApi) {
        if (api is VoicechatServerApi) {
            this.serverApi = api
            logger.info("SVC server API initialized")
        }
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket)
        registration.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected)
        registration.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnected)
    }

    // --- Event Handlers ---

    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val connection = event.connection
        val playerUuid = connection.player.uuid
        val bukkitPlayer = connection.player.player as? Player
        val playerName = bukkitPlayer?.name ?: playerUuid.toString()

        // Register this player as an SVC user
        plugin.sessionManager.register(playerUuid, playerName, ModType.SIMPLE_VOICE_CHAT)
        logger.info("SVC player connected: $playerName")
    }

    private fun onPlayerDisconnected(event: PlayerDisconnectedEvent) {
        val playerUuid = event.playerUuid

        // Remove only the SVC mod type; session is fully removed only when all mod types are gone
        plugin.sessionManager.unregister(playerUuid, ModType.SIMPLE_VOICE_CHAT)

        // Close any outbound channels for this player
        outboundChannels.remove(playerUuid)?.let { channel ->
            channel.flush()
            logger.fine("Closed outbound channel for disconnected SVC player $playerUuid")
        }

        // Signal audio end on PV side for this player's outbound source
        plugin.audioRelay.pvAdapter?.cleanupSource(playerUuid)
    }

    /**
     * Called when an SVC player sends a microphone packet.
     * Relay this audio to PV players via the AudioRelay.
     */
    private fun onMicrophonePacket(event: MicrophonePacketEvent) {
        val senderConnection = event.senderConnection ?: return
        val senderUuid = senderConnection.player.uuid
        val packet = event.packet
        val opusData = packet.opusEncodedData
        if (opusData.isEmpty()) return
        val whispering = packet.isWhispering

        // Touch session to keep it alive
        plugin.sessionManager.getSession(senderUuid)?.touch()

        // Get the Bukkit player for position info
        val bukkitPlayer = Bukkit.getPlayer(senderUuid) ?: return

        // Get the configured distance
        val api = serverApi ?: return
        val distance = api.voiceChatDistance.toFloat()

        // Relay to PV players
        plugin.audioRelay.relaySvcToPv(
            senderUuid = senderUuid,
            senderPlayer = bukkitPlayer,
            opusData = opusData,
            sequenceNumber = 0, // SVC MicrophonePacket doesn't expose sequence to API
            distance = distance,
            whispering = whispering
        )
    }

    // --- Outbound: Send audio FROM a PV player TO SVC clients ---

    /**
     * Send audio from a PV player to nearby SVC clients using EntityAudioChannel.
     *
     * @return true if audio was sent successfully
     */
    fun sendAudioFromExternalPlayer(
        senderUuid: UUID,
        senderPlayer: Player,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Float
    ): Boolean {
        val api = serverApi ?: return false

        // Get existing channel or create a new one
        var channel = outboundChannels[senderUuid]
        if (channel == null) {
            val entity = api.fromEntity(senderPlayer)
            val channelId = UUID.nameUUIDFromBytes("voice-bridge-$senderUuid".toByteArray())
            val newChannel = api.createEntityAudioChannel(channelId, entity)
            if (newChannel == null) {
                logger.warning("Failed to create EntityAudioChannel for PV player $senderUuid")
                BridgeMetrics.droppedFrames.incrementAndGet()
                return false
            }
            newChannel.distance = distance
            // Set filter once at creation — only send to SVC players who are NOT dual-mod
            newChannel.setFilter { serverPlayer ->
                val session = plugin.sessionManager.getSession(serverPlayer.uuid)
                session != null && session.hasModType(ModType.SIMPLE_VOICE_CHAT) && !session.isDualMod()
            }
            outboundChannels[senderUuid] = newChannel
            channel = newChannel
        }

        channel.distance = distance
        channel.send(opusData)
        return true
    }

    /**
     * Clean up resources for a PV player who stopped talking.
     */
    fun flushChannel(senderUuid: UUID) {
        outboundChannels[senderUuid]?.flush()
    }

    /**
     * Remove channel for a player (e.g., on disconnect).
     */
    fun removeChannel(senderUuid: UUID) {
        outboundChannels.remove(senderUuid)?.flush()
    }

    fun shutdown() {
        outboundChannels.values.forEach { it.flush() }
        outboundChannels.clear()
        logger.info("SVC adapter shut down")
    }
}
