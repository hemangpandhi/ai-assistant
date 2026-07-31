package com.tcs.vehicleassistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationalIntentTest {

    @Test
    fun emotionalWellness_matchesSadAndNotFeelingGood() {
        assertTrue(ConversationalIntent.isEmotionalOrWellness("I am not feeling good"))
        assertTrue(ConversationalIntent.isEmotionalOrWellness("Feeling sad"))
        assertTrue(ConversationalIntent.isEmotionalOrWellness("I'm stressed"))
    }

    @Test
    fun emotionalWellness_excludesClimateFeelings() {
        assertFalse(ConversationalIntent.isEmotionalOrWellness("I'm feeling cold"))
        assertFalse(ConversationalIntent.isEmotionalOrWellness("I am too hot"))
        assertFalse(ConversationalIntent.isEmotionalOrWellness("feeling warm"))
    }

    @Test
    fun openChat_matchesGreetingAndIncludesWellness() {
        assertTrue(ConversationalIntent.isOpenChat("how are you"))
        assertTrue(ConversationalIntent.isOpenChat("I'm bored"))
        assertTrue(ConversationalIntent.isOpenChat("talk to me"))
        assertTrue(ConversationalIntent.isOpenChat("i am not feeling good"))
    }

    @Test
    fun openChat_excludesCabinCommandsAndClimate() {
        assertFalse(ConversationalIntent.isOpenChat("play some music"))
        assertFalse(ConversationalIntent.isOpenChat("turn on the AC"))
        assertFalse(ConversationalIntent.isOpenChat("I'm feeling cold"))
    }

    @Test
    fun asrGarbage_flagsConsonantHashButNotChatOrCabin() {
        assertTrue(ConversationalIntent.isLikelyAsrGarbage("xkcdq"))
        assertFalse(ConversationalIntent.isLikelyAsrGarbage("play music"))
        assertFalse(ConversationalIntent.isLikelyAsrGarbage("how are you"))
        assertFalse(ConversationalIntent.isLikelyAsrGarbage("and being said."))
    }
}
