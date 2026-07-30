package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the microphone handoff bug: the voice session sent `ACTION_STOP_LISTENING`
 * while `WakeWordService` only recognised `ACTION_STOP`, so the wake-word process kept its
 * `AudioRecord` open and speech recognition could never acquire the microphone. Any future rename
 * that drops one of these aliases fails here instead of on the device.
 */
class WakeWordActionTest {

    private val actions = AssistantConfig.WakeWordAction

    @Test
    fun `legacy stop aliases still release the microphone`() {
        assertTrue(actions.isStop("ACTION_STOP"))
        assertTrue(actions.isStop("ACTION_STOP_LISTENING"))
        assertTrue(actions.isStop(actions.STOP))
    }

    @Test
    fun `legacy pause aliases are recognised`() {
        assertTrue(actions.isPause("ACTION_PAUSE"))
        assertTrue(actions.isPause("ACTION_PAUSE_LISTENING"))
        assertTrue(actions.isPause(actions.PAUSE))
    }

    @Test
    fun `legacy restart aliases are recognised`() {
        assertTrue(actions.isRestart("ACTION_RESTART"))
        assertTrue(actions.isRestart("ACTION_RESTART_LISTENING"))
        assertTrue(actions.isRestart(actions.RESTART))
    }

    @Test
    fun `stop pause and restart are mutually exclusive`() {
        // Pause must not be treated as stop: pause releases the microphone but keeps the service
        // alive, whereas stop tears it down and needs an explicit restart.
        assertFalse(actions.isStop(actions.PAUSE))
        assertFalse(actions.isStop(actions.RESTART))
        assertFalse(actions.isPause(actions.STOP))
        assertFalse(actions.isPause(actions.RESTART))
        assertFalse(actions.isRestart(actions.STOP))
        assertFalse(actions.isRestart(actions.PAUSE))
    }

    @Test
    fun `a null or unknown action matches nothing`() {
        for (action in listOf(null, "", "com.example.SOMETHING_ELSE", actions.START)) {
            assertFalse("isStop($action)", actions.isStop(action))
            assertFalse("isPause($action)", actions.isPause(action))
            assertFalse("isRestart($action)", actions.isRestart(action))
        }
    }

    @Test
    fun `action strings are namespaced so they cannot collide with system broadcasts`() {
        val declared = listOf(actions.START, actions.STOP, actions.PAUSE, actions.RESTART, actions.DETECTED_BROADCAST)
        for (action in declared) {
            assertTrue("$action must be package-namespaced", action.startsWith("com.tcs.vehicleassistant."))
        }
        assertEquals("action strings must be distinct", declared.size, declared.distinct().size)
    }
}
