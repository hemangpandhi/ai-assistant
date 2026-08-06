package com.assistant.api.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolFaceCueMapperTest {

    @Test
    fun musicReply_mapsToMusic() {
        assertEquals(
            "music",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "Great choice — putting some music on for you!",
            ),
        )
        assertEquals(
            "music",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "Great choice — putting on jazz for you!",
            ),
        )
        assertEquals(
            "music",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "Sure — playing something calming for you.",
            ),
        )
    }

    @Test
    fun navigationReply_mapsToNavigate() {
        assertEquals(
            "navigate",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "Getting you on the road to home — hang tight.",
            ),
        )
    }

    @Test
    fun searchNearbyReply_mapsToSearch() {
        assertEquals(
            "search",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "I found these options nearby: 1. Cafe. Which one would you like to navigate to?",
            ),
        )
    }

    @Test
    fun pauseOrUnrelated_returnsNull() {
        assertNull(ToolFaceCueMapper.iconIdFromSpokenText("Music paused."))
        assertNull(ToolFaceCueMapper.iconIdFromSpokenText("Music stopped."))
        assertNull(ToolFaceCueMapper.iconIdFromSpokenText("Got it, I've remembered that."))
    }

    @Test
    fun climateReplies_mapToHvacIcons() {
        assertEquals(
            "heat",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "There we go — warming things up for you.",
            ),
        )
        assertEquals(
            "ac",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "Cooling it down — you should feel better soon.",
            ),
        )
        assertEquals(
            "thermostat",
            ToolFaceCueMapper.iconIdFromSpokenText(
                "Done — I've set it to 72 degrees for you.",
            ),
        )
    }
}
