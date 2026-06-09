package io.pfaumc.voicebridge.adapter

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Re-paces bursty inbound audio frames into a steady cadence before they are forwarded onward.
 *
 * Simple Voice Chat delivers microphone packets on its own network thread and the spacing is not
 * guaranteed to be even. Plasmo Voice clients buffer only a few packets before draining, so bursty
 * arrival starves the client buffer and triggers repeated packet-loss concealment — audible as rapid
 * crackling. This buffer holds a small lead of frames per speaker and releases exactly one frame per
 * tick, smoothing the cadence the client sees.
 *
 * A stream pre-buffers [leadFrames] frames before it starts draining. If it empties while draining it
 * reverts to pre-buffering, so a pause between utterances re-arms the lead rather than underflowing.
 */
class FrameRepacer(
    private val frameIntervalMs: Long,
    private val leadFrames: Int,
    private val maxFrames: Int,
    private val sink: (UUID, ByteArray, Short) -> Unit
) {
    private val logger = Logger.getLogger("VoiceBridge-Pacer")

    private class Frame(val opus: ByteArray, val distance: Short)

    private class Stream {
        val queue = ArrayDeque<Frame>()
        var draining = false
    }

    private val streams = ConcurrentHashMap<UUID, Stream>()
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "VoiceBridge-Pacer").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(::tick, frameIntervalMs, frameIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    fun enqueue(speaker: UUID, opus: ByteArray, distance: Short) {
        val stream = streams.computeIfAbsent(speaker) { Stream() }
        synchronized(stream) {
            stream.queue.addLast(Frame(opus, distance))
            // Bound latency under clock drift: drop the oldest frame if the queue runs away.
            while (stream.queue.size > maxFrames) stream.queue.removeFirst()
        }
    }

    fun remove(speaker: UUID) {
        streams.remove(speaker)
    }

    private fun tick() {
        for ((speaker, stream) in streams) {
            val frame = synchronized(stream) {
                if (!stream.draining) {
                    if (stream.queue.size >= leadFrames) stream.draining = true
                    else return@synchronized null
                }
                if (stream.queue.isEmpty()) {
                    stream.draining = false
                    null
                } else {
                    stream.queue.removeFirst()
                }
            } ?: continue

            try {
                sink(speaker, frame.opus, frame.distance)
            } catch (e: Exception) {
                logger.fine("Pacer sink error for $speaker: ${e.message}")
            }
        }
    }

    fun shutdown() {
        executor?.shutdownNow()
        executor = null
        streams.clear()
    }
}
