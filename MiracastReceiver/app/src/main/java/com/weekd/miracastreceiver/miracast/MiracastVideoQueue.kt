package com.weekd.miracastreceiver.miracast

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Keeps codec backpressure off the TS demux thread. All offers come from that thread. */
internal class MiracastVideoQueue(private val capacity: Int = 12) {
    sealed class Event {
        data class AccessUnit(val data: ByteArray, val ptsUs: Long) : Event()
        data object Discontinuity : Event()
    }

    private val queue = ArrayBlockingQueue<Event>(capacity)

    @Volatile
    var droppedUnits = 0L
        private set

    val size: Int get() = queue.size

    init {
        require(capacity >= 2)
    }

    fun offer(data: ByteArray, ptsUs: Long) {
        val unit = Event.AccessUnit(data, ptsUs)
        if (queue.offer(unit)) return

        // The queued frames are already late. Drop them together, so the decoder
        // can resume from a clean keyframe instead of displaying a stale GOP.
        discardQueued()
        queue.offer(Event.Discontinuity)
        queue.offer(unit)
    }

    fun discontinuity() {
        discardQueued()
        queue.offer(Event.Discontinuity)
    }

    fun poll(timeoutMs: Long): Event? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun clear() = queue.clear()

    private fun discardQueued() {
        val removed = ArrayList<Event>(capacity)
        queue.drainTo(removed)
        droppedUnits += removed.count { it is Event.AccessUnit }
    }
}
