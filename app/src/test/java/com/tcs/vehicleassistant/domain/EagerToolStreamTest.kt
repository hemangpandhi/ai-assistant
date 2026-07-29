package com.tcs.vehicleassistant.domain

import com.tcs.vehicleassistant.utils.ToolCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies mid-stream eager tool extraction — the contract ToolLoop relies on.
 */
class EagerToolStreamTest {

    @Test
    fun closedToolTagIsExecutableWhileStreamContinues() {
        val stream = StringBuilder()
        stream.append("Cooling the cabin. ")
        assertTrue(ToolCallParser.extractCompleteToolCalls(stream.toString()).isEmpty())

        stream.append("<TOOL>setTemperature(68)")
        assertTrue(
            "Incomplete tag must not fire",
            ToolCallParser.extractCompleteToolCalls(stream.toString()).isEmpty(),
        )

        stream.append("</TOOL> Enjoy the breeze.")
        val calls = ToolCallParser.extractCompleteToolCalls(stream.toString())
        assertEquals(1, calls.size)
        assertEquals("setTemperature(68)", calls[0].invocation)

        val display = ToolCallParser.stripToolTags(stream.toString())
        assertFalse(display.contains("<TOOL>"))
        assertTrue(display.contains("Cooling the cabin."))
        assertTrue(display.contains("Enjoy the breeze."))
    }

    @Test
    fun secondClosedToolIsAlsoEager() {
        val text =
            "OK. <TOOL>turnOnAC()</TOOL> and <TOOL>playMusic(jazz)</TOOL> done."
        val calls = ToolCallParser.extractCompleteToolCalls(text)
        assertEquals(2, calls.size)
        assertEquals("turnOnAC()", calls[0].invocation)
        assertEquals("playMusic(jazz)", calls[1].invocation)
    }

    @Test
    fun speechPresenterCleansToolAndMoodTags() {
        // SpeechPresenter needs IAudioManager — exercise cleanDisplay via a no-op stub.
        val presenter = SpeechPresenter(object : com.tcs.vehicleassistant.hardware.IAudioManager {
            override fun initialize(onSuccess: () -> Unit, onError: () -> Unit) = Unit
            override fun startListening() = Unit
            override fun stopListening() = Unit
            override fun destroySpeechRecognizer() = Unit
            override fun speak(text: String, utteranceId: String) = Unit
            override fun playSilentUtterance(durationMs: Long, utteranceId: String) = Unit
            override fun stopSpeaking() = Unit
            override suspend fun waitUntilFinishedSpeaking() = Unit
            override fun shutdown() = Unit
            override fun setUtteranceListener(
                onStart: (String) -> Unit,
                onDone: (String) -> Unit,
                onError: (String) -> Unit,
                onRangeStart: (String, Int, Int, Int) -> Unit,
            ) = Unit
            override fun setRecognitionListener(
                onReadyForSpeech: () -> Unit,
                onBeginningOfSpeech: () -> Unit,
                onEndOfSpeech: () -> Unit,
                onResult: (String) -> Unit,
                onEmptyResult: () -> Unit,
                onError: (Int) -> Unit,
                onPartial: (String) -> Unit,
            ) = Unit
        })
        val cleaned = presenter.cleanDisplay(
            "Sure <MOOD>happy</MOOD> <TOOL>openWindows()</TOOL> done.",
        )
        assertFalse(cleaned.contains("<TOOL>"))
        assertFalse(cleaned.contains("<MOOD>"))
        assertTrue(cleaned.contains("Sure"))
    }
}
