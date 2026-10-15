package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiracastVideoQueueTest {
    @Test
    fun overflowDiscardsStaleFramesAndRequiresDecoderResync() {
        val queue = MiracastVideoQueue(3)
        repeat(3) { queue.offer(byteArrayOf(it.toByte()), it.toLong()) }
        queue.offer(byteArrayOf(9), 9)

        assertEquals(3, queue.droppedUnits)
        assertEquals(MiracastVideoQueue.Event.Discontinuity, queue.poll(0))
        val latest = queue.poll(0) as MiracastVideoQueue.Event.AccessUnit
        assertEquals(9L, latest.ptsUs)
        assertNull(queue.poll(0))
    }

    @Test
    fun transportDiscontinuityClearsPendingVideoBeforeNextFrame() {
        val queue = MiracastVideoQueue(3)
        queue.offer(byteArrayOf(1), 1)
        queue.discontinuity()
        queue.offer(byteArrayOf(2), 2)

        assertEquals(1, queue.droppedUnits)
        assertEquals(MiracastVideoQueue.Event.Discontinuity, queue.poll(0))
        assertTrue(queue.poll(0) is MiracastVideoQueue.Event.AccessUnit)
        assertNull(queue.poll(0))
    }
}
