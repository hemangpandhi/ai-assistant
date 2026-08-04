package com.tcs.vehicleassistant

import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {

    @Test
    fun isAffirmative_recognizesCommonConfirmations() {
        assertTrue(ConversationMemory.isAffirmative("yes"))
        assertTrue(ConversationMemory.isAffirmative("Sure"))
        assertTrue(ConversationMemory.isAffirmative("do it"))
        assertTrue(ConversationMemory.isAffirmative("yes please"))
        assertFalse(ConversationMemory.isAffirmative("please"))
        assertFalse(ConversationMemory.isAffirmative("maybe later"))
    }

    @Test
    fun isDecline_isTight() {
        assertTrue(ConversationMemory.isDecline("no"))
        assertTrue(ConversationMemory.isDecline("never mind"))
        assertFalse(ConversationMemory.isDecline("I don't want louder music, turn on AC"))
    }

    @Test
    fun isFollowUpQuery_recognizesShortReplies() {
        assertTrue(ConversationMemory.isFollowUpQuery("yes"))
        assertTrue(ConversationMemory.isFollowUpQuery("the second one"))
        assertTrue(ConversationMemory.isFollowUpQuery("take me there"))
        assertFalse(ConversationMemory.isFollowUpQuery("what are the best places to visit in Tokyo for sightseeing"))
    }

    @Test
    fun isFollowUpQuery_allowsSlightlyLongerPicks() {
        assertTrue(ConversationMemory.isFollowUpQuery("the second one please"))
        assertFalse(ConversationMemory.isFollowUpQuery("this is a long conversational message with no follow-up intent"))
    }
}
