package io.pfaumc.voicebridge.session

import io.pfaumc.voicebridge.BridgeMetrics
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

enum class ModType {
    SIMPLE_VOICE_CHAT,
    PLASMO_VOICE
}

data class BridgeSession(
    val playerUuid: UUID,
    val playerName: String,
    val modType: ModType,
    @Volatile var active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var lastActivityAt: Long = System.currentTimeMillis()
) {
    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }
}

class SessionManager {

    private val logger = Logger.getLogger("VoiceBridge")
    private val sessions = ConcurrentHashMap<UUID, BridgeSession>()

    fun register(playerUuid: UUID, playerName: String, modType: ModType): BridgeSession {
        val session = BridgeSession(playerUuid, playerName, modType)
        sessions[playerUuid] = session
        BridgeMetrics.activeSessions.incrementAndGet()
        logger.info("Session created: $playerName (${modType.name})")
        return session
    }

    fun unregister(playerUuid: UUID): BridgeSession? {
        val session = sessions.remove(playerUuid)
        if (session != null) {
            session.active = false
            BridgeMetrics.activeSessions.decrementAndGet()
            logger.info("Session removed: ${session.playerName} (${session.modType.name})")
        }
        return session
    }

    fun getSession(playerUuid: UUID): BridgeSession? = sessions[playerUuid]

    fun getModType(playerUuid: UUID): ModType? = sessions[playerUuid]?.modType

    fun getAllSessions(): Collection<BridgeSession> = sessions.values

    fun getSessionsByMod(modType: ModType): List<BridgeSession> =
        sessions.values.filter { it.modType == modType && it.active }

    fun isRegistered(playerUuid: UUID): Boolean = sessions.containsKey(playerUuid)

    fun clear() {
        sessions.clear()
        BridgeMetrics.activeSessions.set(0)
    }

    /**
     * Cleanup stale sessions (e.g., players who disconnected without proper cleanup).
     * Called periodically from the scheduler.
     */
    fun cleanup() {
        val staleThreshold = System.currentTimeMillis() - STALE_TIMEOUT_MS
        val stale = sessions.entries.filter { it.value.lastActivityAt < staleThreshold && !it.value.active }
        stale.forEach { (uuid, session) ->
            sessions.remove(uuid)
            BridgeMetrics.activeSessions.decrementAndGet()
            logger.fine("Cleaned up stale session: ${session.playerName}")
        }
    }

    companion object {
        private const val STALE_TIMEOUT_MS = 30_000L // 30 seconds
    }
}
