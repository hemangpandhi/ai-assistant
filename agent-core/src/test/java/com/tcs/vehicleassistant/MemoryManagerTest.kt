package com.tcs.vehicleassistant

import org.junit.Assert.*
import org.junit.Test

class MemoryManagerTest {

    @Test
    fun isAffirmative_recognizesCommonConfirmations() {
        assertTrue(MemoryManager.isAffirmative("yes"))
        assertTrue(MemoryManager.isAffirmative("Sure"))
        assertTrue(MemoryManager.isAffirmative("do it"))
        assertTrue(MemoryManager.isAffirmative("yes please"))
        assertFalse(MemoryManager.isAffirmative("please"))
        assertFalse(MemoryManager.isAffirmative("maybe later"))
    }

    @Test
    fun isDecline_isTight() {
        assertTrue(MemoryManager.isDecline("no"))
        assertTrue(MemoryManager.isDecline("never mind"))
        assertFalse(MemoryManager.isDecline("I don't want louder music, turn on AC"))
    }

    @Test
    fun isFollowUpQuery_recognizesShortReplies() {
        assertTrue(MemoryManager.isFollowUpQuery("yes"))
        assertTrue(MemoryManager.isFollowUpQuery("the second one"))
        assertTrue(MemoryManager.isFollowUpQuery("take me there"))
        assertFalse(MemoryManager.isFollowUpQuery("what are the best places to visit in Tokyo for sightseeing"))
    }

    @Test
    fun isFollowUpQuery_allowsSlightlyLongerPicks() {
        assertTrue(MemoryManager.isFollowUpQuery("the second one please"))
        assertFalse(MemoryManager.isFollowUpQuery("this is a long conversational message with no follow-up intent"))
    }
}
