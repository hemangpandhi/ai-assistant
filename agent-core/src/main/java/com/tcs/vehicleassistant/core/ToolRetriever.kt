package com.tcs.vehicleassistant.core

import kotlin.math.ln

/**
 * Lexical BM25 retrieval over the tool catalogue, used to decide which tools are injected into the
 * system prompt for a given utterance.
 *
 * This replaces `SemanticSearchManager`, whose MediaPipe `TextEmbedder` was commented out to avoid
 * a JNI collision with `tasks-vision`. With the embedder gone `embedText` always returned null, so
 * the "semantic search" fallback silently degraded to `getAllTools().take(topK)` — an arbitrary
 * slice of the catalogue with no relation to the query. BM25 over keywords and aliases needs no
 * native dependency, runs in microseconds, and is deterministic enough to unit test.
 *
 * The implementation is intentionally free of Android and Koin references so the ranking can be
 * asserted directly on the JVM.
 */
object ToolRetriever {

    /** BM25 term-frequency saturation. 1.2 is the standard default. */
    private const val K1 = 1.2

    /** BM25 length-normalization strength. 0.75 is the standard default. */
    private const val B = 0.75

    /**
     * Function words carry no routing signal, and in a catalogue this small they would otherwise
     * pick up a misleadingly high inverse document frequency: a description that happens to contain
     * "the" would outrank a tool that actually matches the driver's noun.
     */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "you", "your", "can", "could", "would", "please", "with", "that",
        "this", "there", "here", "are", "was", "were", "has", "have", "had", "its", "it's",
        "from", "into", "onto", "out", "off", "not", "but", "all", "any", "some", "then",
        "than", "too", "very", "just", "now", "get", "got", "let", "lets", "me", "my", "mine",
        "our", "ours", "his", "her", "hers", "them", "they", "their", "who", "what", "when",
        "where", "why", "how", "which", "will", "shall", "should", "may", "might", "must",
        "does", "did", "doing", "done", "been", "being", "about", "over", "under", "again"
    )

    /** A retrievable tool reduced to its searchable text. */
    data class Document(val id: String, val terms: List<String>)

    data class ScoredDocument(val id: String, val score: Double)

    /**
     * Splits text into lowercase alphanumeric terms, dropping single characters and stop words.
     */
    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }

    /**
     * Builds a [Document] from a tool's identifier and its keyword/alias/description text.
     * Terms are repeated per source field so a tool matching on several fields outranks one that
     * matches the same word only once.
     */
    fun document(id: String, vararg textFields: String?): Document =
        Document(id, textFields.filterNotNull().flatMap { tokenize(it) })

    /**
     * Ranks [documents] against [query], returning at most [topK] entries with a positive score.
     *
     * An empty result means nothing in the catalogue matched, which callers should treat as
     * "inject the default tool set" rather than "inject an arbitrary slice".
     */
    fun rank(query: String, documents: List<Document>, topK: Int): List<ScoredDocument> {
        if (documents.isEmpty() || topK <= 0) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        val totalDocs = documents.size
        val averageLength = documents.sumOf { it.terms.size }.toDouble() / totalDocs
        if (averageLength == 0.0) return emptyList()

        // Number of documents containing each query term, for inverse document frequency.
        val documentFrequency = queryTerms.distinct().associateWith { term ->
            documents.count { doc -> doc.terms.contains(term) }
        }

        return documents
            .map { doc ->
                val termCounts = doc.terms.groupingBy { it }.eachCount()
                val docLength = doc.terms.size.toDouble()
                var score = 0.0

                for (term in queryTerms.distinct()) {
                    val termFrequency = termCounts[term]?.toDouble() ?: continue
                    val docsWithTerm = documentFrequency[term] ?: continue
                    if (docsWithTerm == 0) continue

                    // Standard BM25 IDF with the +1 smoothing that keeps it non-negative even
                    // when a term appears in more than half the catalogue.
                    val idf = ln(1.0 + (totalDocs - docsWithTerm + 0.5) / (docsWithTerm + 0.5))
                    val normalization = K1 * (1 - B + B * docLength / averageLength)
                    score += idf * (termFrequency * (K1 + 1)) / (termFrequency + normalization)
                }

                ScoredDocument(doc.id, score)
            }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<ScoredDocument> { it.score }.thenBy { it.id })
            .take(topK)
    }
}
