package com.tcs.vehicleassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectToolResolverTest {

    private val climate = DirectToolResolver.ToolSpec(
        id = "increaseTemperature",
        handlerKey = "increaseTemperature",
        promptString = "<TOOL>increaseTemperature()</TOOL>",
        keywords = listOf("increase temperature", "warmer", "hotter", "warm up"),
        successMessage = "I'm warming it up for you!",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val cooler = DirectToolResolver.ToolSpec(
        id = "decreaseTemperature",
        handlerKey = "decreaseTemperature",
        promptString = "<TOOL>decreaseTemperature()</TOOL>",
        keywords = listOf("decrease temperature", "cooler", "colder", "cool down"),
        successMessage = "Cooling it down.",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val setTemp = DirectToolResolver.ToolSpec(
        id = "setTemperature",
        handlerKey = "setTemperature",
        promptString = "<TOOL>setTemperature(VAL)</TOOL>",
        keywords = listOf("set temperature", "set temp", "temperature to"),
        successMessage = "Temperature set.",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val play = DirectToolResolver.ToolSpec(
        id = "playMusic",
        handlerKey = "playMusic",
        promptString = "<TOOL>playMusic(SONG)</TOOL>",
        keywords = listOf("play music", "play song", "put on"),
        successMessage = "Playing music.",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val stop = DirectToolResolver.ToolSpec(
        id = "stopMusic",
        handlerKey = "stopMusic",
        promptString = "<TOOL>stopMusic()</TOOL>",
        keywords = listOf("stop music", "turn off music", "music off"),
        successMessage = "Stopping music.",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val acOn = DirectToolResolver.ToolSpec(
        id = "turnOnAC",
        handlerKey = "turnOnAC",
        promptString = "<TOOL>turnOnAC()</TOOL>",
        // Short ambient words must not win; phrases are required for production safety.
        keywords = listOf("turn on ac", "ac on", "start ac", "hot", "warm"),
        successMessage = "AC on.",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val call = DirectToolResolver.ToolSpec(
        id = "callContact",
        handlerKey = "callContact",
        promptString = "<TOOL>callContact(NAME)</TOOL>",
        keywords = listOf("call", "phone"),
        successMessage = "Calling.",
        requiresConfirmation = false,
        requiresAgenticLoop = false,
        directExecutable = true,
    )

    private val catalogue = listOf(climate, cooler, setTemp, play, stop, acOn, call)

    @Test
    fun warmer_executesIncreaseTemperature() {
        val outcome = DirectToolResolver.resolve("warmer", catalogue)
        val hit = (outcome as DirectToolResolver.Outcome.Execute).hit
        assertEquals("increaseTemperature()", hit.toolCall)
        assertEquals("warmer", hit.matchedKeyword)
    }

    @Test
    fun setTemperature_fillsVal() {
        val hit = (DirectToolResolver.resolve("set temperature to 72", catalogue)
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("setTemperature(72)", hit.toolCall)
    }

    @Test
    fun playJazz_fillsSong() {
        val playWithVerb = play.copy(keywords = listOf("play music", "play"))
        val hit = (DirectToolResolver.resolve("play jazz", listOf(playWithVerb))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(jazz)", hit.toolCall)
        assertTrue(hit.spokenResponse.contains("jazz", ignoreCase = true))
    }

    @Test
    fun playArijitSinghMusic_fillsArtistNotGenericMusic() {
        val playWithVerb = play.copy(keywords = listOf("play music", "play"))
        val hit = (DirectToolResolver.resolve("play arijit singh music", listOf(playWithVerb))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(arijit singh)", hit.toolCall)
        assertTrue(
            "spoken reply must mention the artist, got: ${hit.spokenResponse}",
            hit.spokenResponse.contains("arijit", ignoreCase = true),
        )
        assertTrue(!hit.toolCall.equals("playMusic(music)", ignoreCase = true))
    }

    @Test
    fun playMusicByAdele_fillsArtist() {
        val playWithVerb = play.copy(keywords = listOf("play music", "play"))
        val hit = (DirectToolResolver.resolve("play music by Adele", listOf(playWithVerb))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(adele)", hit.toolCall)
    }

    @Test
    fun playArijitSinghMusic_stripsTrailingMusicAndNamesArtist() {
        val playWithVerb = play.copy(keywords = listOf("play music", "play", "play song"))
        val hit = (DirectToolResolver.resolve("play arijit singh music", listOf(playWithVerb, stop))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(arijit singh)", hit.toolCall)
        assertTrue(
            "spoken reply should name the artist, was: ${hit.spokenResponse}",
            hit.spokenResponse.contains("arijit singh", ignoreCase = true),
        )
    }

    @Test
    fun playSongsByArtist_extractsArtist() {
        val playWithVerb = play.copy(keywords = listOf("play", "play music"))
        val hit = (DirectToolResolver.resolve("play songs by taylor swift", listOf(playWithVerb))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(taylor swift)", hit.toolCall)
    }

    @Test
    fun playMusicBare_defaultsToMusicArg() {
        val hit = (DirectToolResolver.resolve("play music", listOf(play, stop))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(music)", hit.toolCall)
    }

    @Test
    fun shortPlayVerb_withSongStillExecutes() {
        val playVerbOnly = play.copy(keywords = listOf("play"))
        val hit = (DirectToolResolver.resolve("play jazz", listOf(playVerbOnly))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("playMusic(jazz)", hit.toolCall)
    }

    @Test
    fun barePlay_rejected() {
        val playVerbOnly = play.copy(keywords = listOf("play"))
        val outcome = DirectToolResolver.resolve("play", listOf(playVerbOnly))
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun bareHot_rejectedAsTooShort() {
        val outcome = DirectToolResolver.resolve("hot", catalogue)
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun acOn_phrase_executes() {
        val hit = (DirectToolResolver.resolve("ac on", catalogue)
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("turnOnAC()", hit.toolCall)
    }

    @Test
    fun conversational_fallsThrough() {
        val outcome = DirectToolResolver.resolve("tell me a joke about the weather", catalogue)
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun question_fallsThrough() {
        val outcome = DirectToolResolver.resolve("what is the cabin temperature", catalogue)
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun unsupportedArgs_fallThroughEvenIfKeywordMatches() {
        // callContact is direct_executable in this fixture but NAME is unsupported.
        val outcome = DirectToolResolver.resolve("call mom", listOf(call.copy(
            keywords = listOf("call mom", "call"),
        )))
        assertTrue(
            "expected skip for free-form NAME arg, got $outcome",
            outcome is DirectToolResolver.Outcome.Skip,
        )
    }

    @Test
    fun nonOptInTool_ignored() {
        val locked = climate.copy(directExecutable = false)
        val outcome = DirectToolResolver.resolve("warmer", listOf(locked))
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun ambiguousMusicToken_fallsThrough() {
        // Both tools expose a short shared token; neither phrase fully matches.
        val playOnlyMusic = play.copy(keywords = listOf("music"))
        val stopOnlyMusic = stop.copy(keywords = listOf("music"))
        val outcome = DirectToolResolver.resolve("music", listOf(playOnlyMusic, stopOnlyMusic))
        assertTrue(outcome is DirectToolResolver.Outcome.Skip)
    }

    @Test
    fun stopMusic_uniquePhraseWins() {
        val hit = (DirectToolResolver.resolve("stop music", catalogue)
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("stopMusic()", hit.toolCall)
    }

    @Test
    fun relativeWarmer_notConfusedWithSetTemperature() {
        val setTempPrecise = setTemp.copy(keywords = listOf("set temperature", "set temp"))
        val hit = (DirectToolResolver.resolve("warmer", listOf(climate, setTempPrecise))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("increaseTemperature", hit.toolId)
    }

    @Test
    fun feelingCold_executesHandleFeelingCold() {
        val cold = DirectToolResolver.ToolSpec(
            id = "handleFeelingCold",
            handlerKey = "handleFeelingCold",
            promptString = "<TOOL>handleFeelingCold()</TOOL>",
            keywords = listOf("feeling cold", "i am cold", "i am feeling cold", "shivering"),
            successMessage = "Would you like me to turn on the seat heater?",
            requiresConfirmation = false,
            requiresAgenticLoop = false,
            directExecutable = true,
        )
        val hit = (DirectToolResolver.resolve("I am feeling cold", listOf(climate, cold))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("handleFeelingCold()", hit.toolCall)
    }

    @Test
    fun weatherCityPlaceholder_fillsHereOrCity() {
        val weather = DirectToolResolver.ToolSpec(
            id = "getWeather",
            handlerKey = "getWeather",
            promptString = "<TOOL>getWeather(CITY)</TOOL>",
            keywords = listOf("what is the weather", "weather in", "weather"),
            successMessage = "Opening weather.",
            requiresConfirmation = false,
            requiresAgenticLoop = false,
            directExecutable = true,
        )
        val bare = (DirectToolResolver.resolve("what is the weather", listOf(weather))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("getWeather(here)", bare.toolCall)
        val tokyo = (DirectToolResolver.resolve("weather in Tokyo", listOf(weather))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("getWeather(tokyo)", tokyo.toolCall)
        assertTrue(
            DirectToolResolver.resolve("What's the weather?", listOf(weather))
                is DirectToolResolver.Outcome.Execute,
        )

        val gujarat = (DirectToolResolver.resolve("what's the current weather in Gujarat", listOf(weather))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("getWeather(gujarat)", gujarat.toolCall)

        val currentIn = (DirectToolResolver.resolve("current weather in Gujarat", listOf(weather))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("getWeather(gujarat)", currentIn.toolCall)

        val tokyoCurrent = (DirectToolResolver.resolve("what is the current weather in Tokyo", listOf(weather))
            as DirectToolResolver.Outcome.Execute).hit
        assertEquals("getWeather(tokyo)", tokyoCurrent.toolCall)
    }

    @Test
    fun extractCityArg_currentWeatherVariants() {
        assertEquals(
            "gujarat",
            DirectToolResolver.extractCityArg(
                DirectToolResolver.normalize("what's the current weather in Gujarat"),
            ),
        )
        assertEquals(
            "gujarat",
            DirectToolResolver.extractCityArg(
                DirectToolResolver.normalize("current weather in Gujarat"),
            ),
        )
        assertEquals(
            "tokyo",
            DirectToolResolver.extractCityArg(
                DirectToolResolver.normalize("what is the current weather in Tokyo"),
            ),
        )
        assertEquals(
            "tokyo",
            DirectToolResolver.extractCityArg(
                DirectToolResolver.normalize("whats the weather in tokyo"),
            ),
        )
    }
}
