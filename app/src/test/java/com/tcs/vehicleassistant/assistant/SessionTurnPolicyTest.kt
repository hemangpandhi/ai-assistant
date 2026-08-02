package com.tcs.vehicleassistant.assistant

import com.tcs.vehicleassistant.controller.ViewModelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTurnPolicyTest {

    @Test
    fun startListening_continuesSession() {
        assertEquals(
            SessionTurnPolicy.Continue,
            sessionTurnPolicyFor(ViewModelEvent.StartListening),
        )
    }

    @Test
    fun finishSession_completesSession() {
        assertEquals(
            SessionTurnPolicy.Complete,
            sessionTurnPolicyFor(ViewModelEvent.FinishSession),
        )
    }

    @Test
    fun otherEvents_haveNoTurnPolicy() {
        assertNull(sessionTurnPolicyFor(ViewModelEvent.SetInputText("play music")))
        assertNull(sessionTurnPolicyFor(ViewModelEvent.ShowToast("hi")))
        assertNull(sessionTurnPolicyFor(ViewModelEvent.SetInputEnabled(true)))
    }
}
