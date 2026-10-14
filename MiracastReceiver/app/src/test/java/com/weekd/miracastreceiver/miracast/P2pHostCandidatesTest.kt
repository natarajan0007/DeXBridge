package com.weekd.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class P2pHostCandidatesTest {

    @Test
    fun includesGroupOwnerAddressAndSkipsTheLocalDevice() {
        val candidates = p2pHostCandidates("192.168.49.", "192.168.49.22")

        assertEquals("192.168.49.1", candidates.first())
        assertFalse(candidates.contains("192.168.49.22"))
        assertEquals("192.168.49.254", candidates.last())
        assertEquals(253, candidates.size)
    }

    @Test
    fun skipsGroupOwnerAddressWhenItIsTheLocalDevice() {
        val candidates = p2pHostCandidates("192.168.49.", "192.168.49.1")

        assertEquals("192.168.49.2", candidates.first())
        assertFalse(candidates.contains("192.168.49.1"))
        assertEquals(253, candidates.size)
    }
}
