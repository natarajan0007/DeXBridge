package com.weekd.miracastreceiver.miracast

import timber.log.Timber

/** Submits video to MediaCodec without blocking transport demux or audio delivery. */
internal class MiracastVideoPipeline(
    private val renderer: MiracastVideoRenderer
) {
    private val queue = MiracastVideoQueue()

    @Volatile
    private var running = false
    private var worker: Thread? = null

    val queuedUnits: Int get() = queue.size
    val droppedUnits: Long get() = queue.droppedUnits

    fun start() {
        if (running) return
        running = true
        worker = Thread({
            try {
                while (running) {
                    val event = try {
                        queue.poll(200)
                    } catch (_: InterruptedException) {
                        break
                    } ?: continue
                    if (!running) break
                    try {
                        when (event) {
                            is MiracastVideoQueue.Event.AccessUnit ->
                                renderer.onAccessUnit(event.data, event.ptsUs)
                            MiracastVideoQueue.Event.Discontinuity -> renderer.onDiscontinuity()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Miracast video decode error")
                        renderer.onDiscontinuity()
                    }
                }
            } finally {
                renderer.release()
                Timber.i("Miracast video worker stopped, dropped ${queue.droppedUnits} access units")
            }
        }, "MiracastVideo").apply { start() }
    }

    fun onAccessUnit(data: ByteArray, ptsUs: Long) {
        if (running) queue.offer(data, ptsUs)
    }

    fun onDiscontinuity() {
        if (running) queue.discontinuity()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(1_000)
        if (worker?.isAlive == true) Timber.w("Miracast video worker still stopping")
        worker = null
        queue.clear()
    }
}
