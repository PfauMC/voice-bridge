package io.pfaumc.voicebridge.adapter

import su.plo.voice.api.server.player.VoiceServerPlayer
import su.plo.voice.api.server.socket.UdpServerConnection
import su.plo.voice.proto.packets.Packet
import su.plo.voice.proto.packets.udp.serverbound.ServerPacketUdpHandler
import java.net.InetSocketAddress
import java.util.*

/**
 * Minimal fake UdpServerConnection for SVC-only players.
 *
 * Registered with PV's UdpConnectionManager so that PV clients see a voice-connected icon
 * above these players. All send/handle methods are no-ops because audio goes through the bridge,
 * not through PV's UDP transport.
 */
class BridgedUdpConnection(
    private val player: VoiceServerPlayer
) : UdpServerConnection {

    private val secret: UUID = UUID.randomUUID()
    private val stubAddress = InetSocketAddress("127.0.0.1", 0)

    override fun getPlayer(): VoiceServerPlayer = player

    override fun getSecret(): UUID = secret

    override fun getRemoteAddress(): InetSocketAddress = stubAddress

    override fun getConnectionAddress(): InetSocketAddress = stubAddress

    override fun setRemoteAddress(remoteAddress: InetSocketAddress) {}

    override fun setConnectionAddress(connectionAddress: InetSocketAddress) {}

    override fun sendPacket(packet: Packet<*>) {
        // No-op: audio goes through bridge, not UDP
    }

    override fun handlePacket(packet: Packet<ServerPacketUdpHandler>) {
        // No-op
    }

    override fun disconnect() {
        // No-op: lifecycle managed by PvAdapter
    }

    override fun isConnected(): Boolean = true

    override fun getKeepAlive(): Long = System.currentTimeMillis()

    override fun getSentKeepAlive(): Long = System.currentTimeMillis()

    override fun setSentKeepAlive(keepAlive: Long) {
        // No-op
    }

    override fun getLastReceivedPacketTimestamp(): Long = System.currentTimeMillis()
}
