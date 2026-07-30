package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationPolicyTest {

    @Test
    fun affirmatives_includePunctuationAndPoliteness() {
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("yes"))
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("Yes!"))
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("yes please"))
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("ok go ahead"))
        assertEquals(ConfirmationPolicy.Reply.AFFIRM, ConfirmationPolicy.classify("yeah sure"))
    }

    @Test
    fun barePlease_isNotAffirmative() {
        assertFalse(ConfirmationPolicy.isAffirmative("please"))
        assertEquals(ConfirmationPolicy.Reply.OTHER, ConfirmationPolicy.classify("please"))
    }

    @Test
    fun declines_areExplicit() {
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("no"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("nope"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("cancel"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("never mind"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("don't do that"))
    }

    @Test
    fun dontInsideLongerCommand_isNotDecline() {
        // Must not cancel a pending confirm when the user issues a new cabin command.
        assertEquals(
            ConfirmationPolicy.Reply.OTHER,
            ConfirmationPolicy.classify("I don't want louder music, turn on AC"),
        )
        assertEquals(
            ConfirmationPolicy.Reply.OTHER,
            ConfirmationPolicy.classify("increase temperature"),
        )
        assertEquals(
            ConfirmationPolicy.Reply.OTHER,
            ConfirmationPolicy.classify("volume up"),
        )
    }

    @Test
    fun declineBeatsAffirmWhenAmbiguousPrefix() {
        // "no" alone is decline; classify checks decline first.
        assertTrue(ConfirmationPolicy.isDecline("no thanks"))
        assertEquals(ConfirmationPolicy.Reply.DECLINE, ConfirmationPolicy.classify("no thanks"))
    }
}
