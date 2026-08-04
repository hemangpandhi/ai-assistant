package com.tcs.vehicleassistant.core

/**
 * Registry-driven direct tool execution: when a query uniquely matches a tool that the OEM has
 * marked `direct_executable`, act immediately and skip LiteRT.
 *
 * This is the production path for sub-second cabin commands. Scalability comes from the skills
 * registry — new tools opt in with keywords + `direct_executable: true`; no Kotlin phrase lists.
 *
 * Safety over recall: ambiguous, confirmatory, multi-arg, or conversational turns fall through to
 * the LLM rather than risk a wrong VHAL write.
 */
class DirectToolResolver {
    companion object {
        private val testInstance = DirectToolResolver()
        fun resolve(query: String, specs: List<ToolSpec>, policy: Policy): Outcome = testInstance.resolve(query, specs, policy)
        fun normalize(query: String): String = testInstance.normalize(query)
        fun collapseAsrRepeats(query: String): String = testInstance.collapseAsrRepeats(query)
        fun extractCityArg(query: String): String? = testInstance.extractCityArg(query)
        fun extractAmenityArg(query: String): String? = testInstance.extractAmenityArg(query)
        fun extractSongArg(query: String): String? = testInstance.extractSongArg(query)
        fun containsWholePhrase(a: String, b: String): Boolean = testInstance.containsWholePhrase(a, b)
        fun spokenResponseFor(tool: ToolSpec, toolCall: String): String = testInstance.spokenResponseFor(tool, toolCall)
    }


    data class ToolSpec(
        val id: String,
        val handlerKey: String,
        val promptString: String,
        val keywords: List<String>,
        val successMessage: String?,
        val requiresConfirmation: Boolean,
        val requiresAgenticLoop: Boolean,
        val directExecutable: Boolean,
    )

    data class Policy(
        val enabled: Boolean = true,
        /** Reject matches whose best keyword is shorter than this (blocks "hot", "fan", …). */
        val minKeywordChars: Int = 5,
        /** Soft cap so long chat turns never short-circuit into a tool. */
        val maxQueryWords: Int = 12,
        val maxQueryChars: Int = 100,
        /**
         * Winner's matched-keyword length must beat the runner-up by at least this many characters,
         * or the runner-up must be absent. Prevents "play music" vs "stop music" ties on "music".
         */
        val minKeywordMargin: Int = 3,
        /** Max fan level used for DirectTool "max" utterances; OEM override via registry. */
        val fanMax: Int = 7,
        /** Max volume percent for DirectTool "max" utterances. */
        val volumeMax: Int = 100,
        /** Default seat-heater level when user says "on" / bare seat-heater. */
        val seatHeaterOnDefault: Int = 2,
        /** Default alert level for ALERT_LEVEL placeholder tools. */
        val alertLevelDefault: Int = 2,
        /** Fallback numeric minimum (fan/volume "min"). */
        val numericMinDefault: Int = 1,
    )

    data class Hit(
        val toolId: String,
        val toolCall: String,
        val spokenResponse: String,
        val matchedKeyword: String,
        val reason: String,
    )

    data class Rejection(val reason: String)

    sealed class Outcome {
        data class Execute(val hit: Hit) : Outcome()
        data class Skip(val rejection: Rejection) : Outcome()
    }

    private val QUESTION_PREFIXES = listOf(
        "what", "why", "how", "when", "where", "who", "which", "tell", "explain",
        "describe", "should", "could you tell", "do you",
    )

    /** Always LLM — never DirectTool even if a keyword substring matches. */
    private val HARD_CHAT_PREFIXES = listOf(
        "tell", "explain", "describe", "should", "could you tell", "do you",
    )

    /**
     * Placeholders that appear inside registry `prompt_string` argument lists. Anything else
     * (NAME, MSG, FACT, …) is considered too free-form for direct execution.
     */
    private val SUPPORTED_PLACEHOLDERS = setOf(
        "VAL", "LEVEL", "SONG", "PLACE_NAME", "CITY", "PCT", "DIRECTION", "AMENITY", "ALERT_LEVEL",
    )

    fun resolve(query: String, tools: Collection<ToolSpec>, policy: Policy = Policy()): Outcome {
        if (!policy.enabled) return Outcome.Skip(Rejection("direct_execution_disabled"))

        // Trailing "?" is common ASR/demo punctuation ("What's the weather?"); only reject
        // mid-utterance question marks that signal a compound interrogative.
        val trimmed = query.trim().trimEnd('?', '!', '.', ',')
        if (trimmed.isEmpty()) return Outcome.Skip(Rejection("empty_query"))
        if (trimmed.contains('?')) return Outcome.Skip(Rejection("question_mark"))

        // Collapse ASR stutter ("play music play music …") before length/word gates so DirectTool
        // still owns the intended short cabin phrase.
        val normalized = collapseAsrRepeats(trimmed)
        if (normalized.length > policy.maxQueryChars) return Outcome.Skip(Rejection("query_too_long"))
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > policy.maxQueryWords) {
            return Outcome.Skip(Rejection("query_word_count"))
        }
        val hardChat = HARD_CHAT_PREFIXES.any {
            normalized == it || normalized.startsWith("$it ")
        }
        if (hardChat) return Outcome.Skip(Rejection("interrogative"))

        val looksSoftInterrogative = QUESTION_PREFIXES.any {
            normalized == it || normalized.startsWith("$it ")
        }

        val candidates = mutableListOf<Pair<ToolSpec, String>>()
        for (tool in tools) {
            if (!tool.directExecutable) continue
            if (tool.requiresConfirmation || tool.requiresAgenticLoop) continue
            val keyword = bestKeywordMatch(normalized, tool.keywords) ?: continue
            if (!isKeywordConfident(keyword, normalized, tool, policy)) continue
            candidates += tool to keyword
        }

        // Soft "what/who …" allowed when a DirectTool keyword hits (weather, identity).
        if (candidates.isEmpty()) {
            return if (looksSoftInterrogative) {
                Outcome.Skip(Rejection("interrogative"))
            } else {
                Outcome.Skip(Rejection("no_keyword_match"))
            }
        }

        candidates.sortByDescending { it.second.length }
        val best = candidates.first()
        val secondLen = candidates.getOrNull(1)?.second?.length ?: 0
        if (secondLen > 0 && best.second.length - secondLen < policy.minKeywordMargin) {
            // Same-length distinct keywords for different tools → also ambiguous.
            val tied = candidates.filter { it.second.length == best.second.length }.map { it.first.id }.distinct()
            if (tied.size > 1) {
                return Outcome.Skip(Rejection("ambiguous_tools:${tied.joinToString(",")}"))
            }
            return Outcome.Skip(Rejection("weak_margin"))
        }

        val toolCall = buildToolCall(best.first, normalized, policy)
            ?: return Outcome.Skip(Rejection("unresolvable_args:${best.first.id}"))

        val spoken = spokenResponseFor(best.first, toolCall)

        return Outcome.Execute(
            Hit(
                toolId = best.first.id,
                toolCall = toolCall,
                spokenResponse = spoken,
                matchedKeyword = best.second,
                reason = "keyword_len=${best.second.length}",
            )
        )
    }

    /**
     * Prefer a song-aware spoken line for media; otherwise use the registry success_message.
     * Static "putting that on for you" hid the artist/title the user actually asked for.
     */
    fun spokenResponseFor(tool: ToolSpec, toolCall: String): String {
        if (tool.handlerKey.equals("playMusic", ignoreCase = true) ||
            toolCall.startsWith("playMusic", ignoreCase = true)
        ) {
            val song = toolCall.substringAfter("(").substringBefore(")").trim()
                .removeSurrounding("\"")
            if (song.isNotBlank() && !song.equals("music", ignoreCase = true) &&
                !song.equals("SONG", ignoreCase = true)
            ) {
                return "Great choice — putting on $song for you!"
            }
        }
        return tool.successMessage?.takeIf { it.isNotBlank() } ?: "Done."
    }

    fun normalize(raw: String): String =
        raw.lowercase()
            // ASR/UI often emits "A/C"; collapse before punctuation strip or it becomes "a c".
            .replace(Regex("""\ba\s*/\s*c\b"""), "ac")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            // "what's" → "what s" after punctuation strip; treat as "whats"
            .replace(Regex("""\bwhat\s+s\b"""), "whats")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Collapses stuttering ASR transcripts that repeat the same short phrase, e.g.
     * "play music play music play music" → "play music", including a truncated trailing copy.
     */
    fun collapseAsrRepeats(raw: String): String {
        val normalized = normalize(raw)
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return normalized

        for (period in 1..(words.size / 2)) {
            if (words.size < period * 2) continue
            val unit = words.subList(0, period)
            var idx = 0
            var copies = 0
            while (idx + period <= words.size &&
                words.subList(idx, idx + period) == unit
            ) {
                copies++
                idx += period
            }
            if (copies < 2) continue
            val rem = words.subList(idx, words.size)
            if (rem.isNotEmpty() && rem != unit.subList(0, rem.size)) continue
            return unit.joinToString(" ")
        }
        return normalized
    }

    /**
     * Longest whole-phrase keyword contained in [normalizedQuery]. Longer phrases win so
     * "increase temperature" outranks a shorter accidental substring.
     */
    fun bestKeywordMatch(normalizedQuery: String, keywords: List<String>): String? {
        var best: String? = null
        for (rawKeyword in keywords) {
            val keyword = normalize(rawKeyword)
            if (keyword.isEmpty()) continue
            if (!containsWholePhrase(normalizedQuery, keyword)) continue
            if (best == null || keyword.length > best.length) best = keyword
        }
        return best
    }

    /**
     * Long keywords always pass. Short verb prefixes (e.g. "play") only pass when the tool's
     * prompt expects a trailing argument and the user actually supplied more text after the verb —
     * so "play jazz" can execute while bare "play" / "hot" cannot.
     */
    fun isKeywordConfident(
        keyword: String,
        normalizedQuery: String,
        tool: ToolSpec,
        policy: Policy,
    ): Boolean {
        if (keyword.length >= policy.minKeywordChars) return true
        if (keyword.length < 3) return false
        val needsTrailing = tool.promptString.contains("SONG", ignoreCase = true) ||
            tool.promptString.contains("PLACE_NAME", ignoreCase = true) ||
            tool.promptString.contains("CITY", ignoreCase = true) ||
            tool.promptString.contains("AMENITY", ignoreCase = true)
        if (!needsTrailing) return false
        return normalizedQuery.startsWith("$keyword ") &&
            normalizedQuery.length >= keyword.length + 2
    }

    fun containsWholePhrase(haystack: String, phrase: String): Boolean {
        if (haystack == phrase) return true
        val pattern = Regex("""(?:^|\s)${Regex.escape(phrase)}(?:\s|$)""")
        return pattern.containsMatchIn(haystack)
    }

    /**
     * Builds `handlerKey(args)` from the tool's prompt template and the user query.
     * Returns null when a required placeholder cannot be filled safely.
     */
    fun buildToolCall(
        tool: ToolSpec,
        normalizedQuery: String,
        policy: Policy = Policy(),
    ): String? {
        val key = tool.handlerKey
        val argTemplate = tool.promptString
            .substringAfter("<TOOL>", missingDelimiterValue = "")
            .substringBefore("</TOOL>", missingDelimiterValue = "")
            .substringAfter("(", missingDelimiterValue = "")
            .substringBefore(")", missingDelimiterValue = "")
            .trim()
            .removeSurrounding("\"")

        if (argTemplate.isEmpty()) return "$key()"

        val tokens = argTemplate.split(',').map { it.trim().removeSurrounding("\"").uppercase() }
        if (tokens.any { it !in SUPPORTED_PLACEHOLDERS }) return null
        if (tokens.size != 1) return null // multi-arg tools stay on the LLM path

        return when (tokens.single()) {
            "VAL", "LEVEL", "PCT" -> {
                val value = extractNumericOrDirectionalArg(normalizedQuery, key, policy) ?: return null
                "$key($value)"
            }
            "SONG" -> {
                val song = extractSongArg(normalizedQuery) ?: "music"
                "$key($song)"
            }
            "PLACE_NAME" -> {
                val place = extractTrailingArg(
                    normalizedQuery,
                    prefixes = listOf(
                        "navigate me to",
                        "take me to the",
                        "take me to",
                        "directions to",
                        "route to",
                        "drive to",
                        "go to",
                        "navigate to",
                        "navigate",
                    ),
                ) ?: return null
                "$key(\"$place\")"
            }
            "CITY" -> {
                val city = extractCityArg(normalizedQuery) ?: "here"
                "$key($city)"
            }
            "DIRECTION" -> {
                val direction = extractAirflowDirection(normalizedQuery) ?: return null
                "$key($direction)"
            }
            "AMENITY" -> {
                // Bare "find nearby" / "craving" default to restaurant; specific cues override.
                val amenity = extractAmenityArg(normalizedQuery) ?: "restaurant"
                "$key($amenity)"
            }
            "ALERT_LEVEL" -> "$key(${policy.alertLevelDefault})"
            else -> null
        }
    }

    private fun extractNumericOrDirectionalArg(
        query: String,
        handlerKey: String,
        policy: Policy,
    ): String? {
        when {
            query.contains("mute") || Regex("""\b(zero|off)\b""").containsMatchIn(query) &&
                handlerKey.contains("Volume", ignoreCase = true) -> return "0"
            Regex("""\b(up|louder|higher|increase|raise)\b""").containsMatchIn(query) &&
                handlerKey.contains("Volume", ignoreCase = true) -> return "up"
            Regex("""\b(down|quieter|lower|decrease|softer)\b""").containsMatchIn(query) &&
                handlerKey.contains("Volume", ignoreCase = true) -> return "down"
            Regex("""\b(max|maximum)\b""").containsMatchIn(query) -> {
                return if (handlerKey.contains("Fan", ignoreCase = true)) {
                    policy.fanMax.toString()
                } else {
                    policy.volumeMax.toString()
                }
            }
            Regex("""\b(min|minimum)\b""").containsMatchIn(query) ->
                return policy.numericMinDefault.toString()
            Regex("""\b(off|disable)\b""").containsMatchIn(query) &&
                handlerKey.contains("Seat", ignoreCase = true) -> return "0"
            Regex("""\b(on|enable)\b""").containsMatchIn(query) &&
                handlerKey.contains("Seat", ignoreCase = true) ->
                return policy.seatHeaterOnDefault.toString()
        }
        Regex("""\b(\d{1,3})\b""").find(query)?.groupValues?.getOrNull(1)?.let { return it }
        // Seat heater without a level but matched "seat heater" → default comfort level.
        if (handlerKey.contains("SeatHeater", ignoreCase = true)) {
            return policy.seatHeaterOnDefault.toString()
        }
        return null
    }

    private fun extractTrailingArg(query: String, prefixes: List<String>): String? {
        for (prefix in prefixes.sortedByDescending { it.length }) {
            if (query == prefix) return null
            if (query.startsWith("$prefix ")) {
                val rest = query.removePrefix("$prefix ").trim()
                    .removePrefix("some ").removePrefix("the ").removePrefix("a ")
                    .trim()
                if (rest.length in 2..60) return rest
            }
        }
        return null
    }

    /**
     * Pulls the music query after play/put-on verbs and strips filler like trailing "music"
     * so "play arijit singh music" becomes a searchable "arijit singh".
     */
    fun extractSongArg(normalizedQuery: String): String? {
        val raw = extractTrailingArg(
            normalizedQuery,
            prefixes = listOf(
                "play songs by",
                "play music by",
                "play song by",
                "turn on music",
                "put on",
                "play some",
                "start playing",
                "turn on",
                "play",
                "start",
            ),
        ) ?: return null

        var song = raw
            .replace(Regex("""^(songs?|tracks?|music|playlist)\s+(by|from)\s+"""), "")
            .replace(Regex("""^(by|from)\s+"""), "")
            .trim()

        // Drop trailing genre/container words that dilute artist/title search.
        song = song
            .replace(Regex("""\s+(music|songs?|tracks?|playlist|audio)$"""), "")
            .trim()

        // "play music" / "play some music" → no specific request.
        if (song.isEmpty() || song in setOf("music", "song", "songs", "track", "tracks", "something")) {
            return null
        }
        return song.takeIf { it.length in 2..60 }
    }

    private fun extractAirflowDirection(query: String): String? = when {
        query.contains("face and floor") || query.contains("floor and face") ||
            query.contains("face and feet") || query.contains("feet and face") -> "face and floor"
        query.contains("defrost") -> "defrost"
        query.contains("floor") || query.contains("feet") -> "floor"
        query.contains("face") -> "face"
        else -> null
    }

    /** City for getWeather; "here" means use LocationManager at execution time. */
    fun extractCityArg(normalizedQuery: String): String? {
        extractTrailingArg(
            normalizedQuery,
            prefixes = listOf(
                "what is the current weather in",
                "whats the current weather in",
                "what is the weather in",
                "whats the weather in",
                "current weather in",
                "current weather for",
                "current weather at",
                "weather forecast in",
                "weather forecast for",
                "forecast in",
                "forecast for",
                "weather in",
                "weather for",
                "weather at",
            ),
        )?.let { return sanitizeCityArg(it) }

        // Mid-phrase fallback when fillers precede the weather preposition.
        Regex("""\b(?:current\s+)?weather\s+(?:in|for|at)\s+(.+)$""")
            .find(normalizedQuery)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return sanitizeCityArg(it) }
        Regex("""\bforecast\s+(?:in|for)\s+(.+)$""")
            .find(normalizedQuery)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return sanitizeCityArg(it) }
        return null
    }

    private fun sanitizeCityArg(raw: String): String? {
        val city = raw.trim()
            .removePrefix("the ")
            .removePrefix("a ")
            .trim()
        return city.takeIf { it.length in 2..60 }
    }

    /**
     * Amenity for searchNearby: trailing phrase after find/nearby verbs, else keyword inference
     * for short cabin asks ("gas station", "i am hungry", "pizza nearby").
     */
    fun extractAmenityArg(normalizedQuery: String): String? {
        extractTrailingArg(
            normalizedQuery,
            prefixes = listOf(
                "find nearby",
                "search nearby",
                "looking for nearby",
                "craving some good",
                "craving some",
                "craving",
                "find a nearby",
                "find me",
                "search for",
                "nearby",
                "find",
            ),
        )?.let { rest ->
            return rest.removeSuffix(" nearby").removePrefix("nearby ").trim().ifBlank { null }
        }
        return when {
            Regex("""\b(gas station|petrol|fuel)\b""").containsMatchIn(normalizedQuery) -> "gas"
            Regex("""\bcharg(e|ing)\b""").containsMatchIn(normalizedQuery) -> "charging"
            Regex("""\bcoffee\b""").containsMatchIn(normalizedQuery) -> "coffee shop"
            Regex("""\bpizza\b""").containsMatchIn(normalizedQuery) -> "pizza"
            Regex("""\b(restaurant|food|hungry|eat)\b""").containsMatchIn(normalizedQuery) -> "restaurant"
            else -> null
        }
    }
}
