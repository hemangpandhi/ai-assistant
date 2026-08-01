package com.tcs.vehicleassistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCriticalToolsTest {

    @Test
    fun marksUnlockTrunkWindows() {
        assertTrue(SafetyCriticalTools.isSafetyCritical("unlockDoors()"))
        assertTrue(SafetyCriticalTools.isSafetyCritical("openTrunk()"))
        assertTrue(SafetyCriticalTools.isSafetyCritical("setWindowPosition(50)"))
        assertTrue(SafetyCriticalTools.isSafetyCritical("openWindowsVent()"))
    }

    @Test
    fun doesNotMarkClimateOrMedia() {
        assertFalse(SafetyCriticalTools.isSafetyCritical("turnOnAC()"))
        assertFalse(SafetyCriticalTools.isSafetyCritical("playMusic(music)"))
        assertFalse(SafetyCriticalTools.isSafetyCritical("setVolumeLevel(up)"))
    }
}
