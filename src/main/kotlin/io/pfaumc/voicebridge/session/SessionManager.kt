package io.pfaumc.voicebridge.session

import io.pfaumc.voicebridge.BridgeMetrics
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

enum class ModType {
    SIMPLE_VOICE_CHAT,
    PLASMO_VOICE
}

class BridgeSession(
    val playerUuid: UUID,
    val playerName: String,
    initialModType: ModType,
    @Volatile var active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var lastActivityAt: Long = System.currentTimeMillis()
) {
    private val modTypes: MutableSet<ModType> = Collections.newSetFromMap(ConcurrentHashMap())

    init {
        modTypes.add(initialModType)
    }

    /** Primary mod type — for backward compat with code that expects a single type. */
    val modType: ModType get() = modTypes.first()

    fun addModType(type: ModType) =
        modTypes.add(type)

    fun removeModType(type: ModType) {
        modTypes.remove(type)
    }

    fun hasModType(type: ModType): Boolean = type in modTypes

    fun isDualMod(): Boolean = modTypes.size >= 2

    fun hasNoMods(): Boolean = modTypes.isEmpty()

    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }
}

class SessionManager {

    private val logger = Logger.getLogger("VoiceBridge")
    private val sessions = ConcurrentHashMap<UUID, BridgeSession>()

    fun register(playerUuid: UUID, playerName: String, modType: ModType): BridgeSession {
        val session = sessions.getOrPut(playerUuid) {
            BridgeMetrics.activeSessions.incrementAndGet()
            logger.info("Session created: $playerName (${modType.name})")
            BridgeSession(playerUuid, playerName, modType)
        }
        if (session.addModType(modType)) {
            logger.info("Session updated: $playerName added ${modType.name} (dual-mod: ${session.isDualMod()})")
        }
        return session
    }

    fun unregister(playerUuid: UUID): BridgeSession? {
        val session = sessions.remove(playerUuid)
        if (session != null) {
            session.active = false
            BridgeMetrics.activeSessions.decrementAndGet()
            logger.info("Session removed: ${session.playerName}")
        }
        return session
    }

    fun unregister(playerUuid: UUID, modType: ModType): BridgeSession? {
        return sessions.compute(playerUuid) { _, session ->
            if (session == null) return@compute null
            session.removeModType(modType)
            if (session.hasNoMods()) {
                session.active = false
                BridgeMetrics.activeSessions.decrementAndGet()
                logger.info("Session removed: ${session.playerName} (last mod ${modType.name} gone)")
                null // remove from map
            } else {
                logger.info("Session updated: ${session.playerName} removed ${modType.name}")
                session
            }
        }
    }

    fun getSession(playerUuid: UUID): BridgeSession? = sessions[playerUuid]

    fun getModType(playerUuid: UUID): ModType? = sessions[playerUuid]?.modType

    fun isDualMod(playerUuid: UUID): Boolean = sessions[playerUuid]?.isDualMod() == true

    fun getAllSessions(): Collection<BridgeSession> = sessions.values

    fun getSessionsByMod(modType: ModType): List<BridgeSession> =
        sessions.values.filter { it.hasModType(modType) && it.active }

    fun isRegistered(playerUuid: UUID): Boolean = sessions.containsKey(playerUuid)

    fun clear() {
        sessions.clear()
        BridgeMetrics.activeSessions.set(0)
    }

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
