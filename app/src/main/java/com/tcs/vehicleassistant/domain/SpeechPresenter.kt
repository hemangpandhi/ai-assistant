package com.tcs.vehicleassistant.domain

import com.tcs.vehicleassistant.hardware.IAudioManager
import com.tcs.vehicleassistant.utils.ToolCallParser

/**
 * Sentence-boundary streaming TTS presenter.
 * Keeps TTS policy out of the agent pipeline reducer.
 */
class SpeechPresenter(
    private val audioManager: IAudioManager,
) {
    private var spokenTextLength = 0
    private var parsedSpokenLength = 0

    fun reset() {
        spokenTextLength = 0
        parsedSpokenLength = 0
    }

    fun speakCompletedSentences(displayMsg: String) {
        val safeStartIndex = minOf(spokenTextLength, displayMsg.length)
        var remainingText = displayMsg.substring(safeStartIndex)
        val sentenceRegex =
            "^(.*?)([.!?]{2,}(?:\\s+|$)|\\n|(?<=[a-zA-Z\\)\\]\\\"])[.,!?](?:\\s+|$))".toRegex()
        var match = sentenceRegex.find(remainingText)
        while (match != null) {
            val sentence = match.value
            spokenTextLength += sentence.length
            val sentenceStartOffset = parsedSpokenLength
            parsedSpokenLength += sentence.length
            audioManager.speak(sentence, "SENTENCE_$sentenceStartOffset")
            remainingText = displayMsg.substring(spokenTextLength)
            match = sentenceRegex.find(remainingText)
        }
    }

    fun speakRemainder(finalMsg: String): Boolean {
        val safeIndex = minOf(spokenTextLength, finalMsg.length)
        val remainingSentence = finalMsg.substring(safeIndex).trim()
        if (remainingSentence.isEmpty()) return false
        val sentenceStartOffset = parsedSpokenLength
        parsedSpokenLength += remainingSentence.length
        spokenTextLength = finalMsg.length
        audioManager.speak(remainingSentence, "SENTENCE_$sentenceStartOffset")
        return true
    }

    fun cleanDisplay(raw: String): String {
        var displayMsg = ToolCallParser.stripToolTags(raw)
        displayMsg = displayMsg.replace(Regex("\\biI\\b"), "I")
        displayMsg = displayMsg.replace(Regex("\\bi can I\\b", RegexOption.IGNORE_CASE), "I can")
        displayMsg = displayMsg.replace(Regex("^i\\s+"), "")
        displayMsg = displayMsg.replace(Regex("^i\\b"), "I")
        return displayMsg.trim()
    }
}
