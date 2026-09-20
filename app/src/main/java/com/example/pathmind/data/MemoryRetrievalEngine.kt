package com.example.pathmind.data

import com.example.pathmind.model.PlaceMemory
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Match type categories for retrieval transparency and explainability.
 */
enum class MatchType {
    EXACT,          // 1.00: Exact string match after normalization
    ALIAS,          // 0.95: Direct controlled alias match (e.g. bicycle -> bike)
    TOKEN_OVERLAP,  // 0.70 - 0.93: Multi-word token overlap or token-level alias match
    FUZZY_TYPO,     // 0.65 - 0.80: Close edit distance (e.g. bkie -> bike)
    PARTIAL,        // 0.40 - 0.60: Substring match
    NONE            // 0.00: No significant match
}

/**
 * Ranked candidate output produced by MemoryRetrievalEngine.
 *
 * @property memory The candidate PlaceMemory
 * @property score The final composite ranking score (baseScore + contextBonus)
 * @property matchType High-level semantic category of the match
 * @property matchReason Human-readable explanation of why this match occurred
 * @property baseScore The pure semantic match score (0.0 - 1.0) before context weighting
 * @property contextBonus The total bonus applied from contextual signals (route affinity, recency)
 */
data class RetrievalCandidate(
    val memory: PlaceMemory,
    val score: Float,
    val matchType: MatchType,
    val matchReason: String,
    val baseScore: Float = score,
    val contextBonus: Float = 0.0f
)

/**
 * Stage 10 (Steps 1 & 2): Smart Offline Memory Retrieval Engine.
 *
 * Ranks saved PlaceMemory objects against a user's natural-language place query.
 * Strictly offline, deterministic, and free of external AI/ML dependencies.
 *
 * Step 1: Semantic Pipeline
 * 1. Normalization (lowercase, apostrophe removal, punctuation stripping, whitespace collapse)
 * 2. Exact match check (Base Score 1.00)
 * 3. Controlled alias dictionary lookup (Base Score 0.95)
 * 4. Multi-word token overlap with stop-word filtering & token aliases (Base Score 0.70 - 0.93)
 * 5. Conservative typo / Damerau-Levenshtein distance (Base Score 0.65 - 0.80)
 * 6. Substring fallback (Base Score 0.40 - 0.60)
 *
 * Step 2: Contextual Signals (Strictly Bounded)
 * - Route Affinity Bonus: +0.03 if candidate belongs to active route
 * - Weak Recency Bonus: up to +0.015 as a tie-breaker for newer memories
 * - Total Context Bonus Cap: +0.045 (guarantees context NEVER overrides semantic correctness)
 */
class MemoryRetrievalEngine {

    companion object {
        /**
         * Small controlled alias dictionary specified for Stage 10.
         * Bidirectionally mapped so query -> memory and memory -> query resolve identically.
         */
        val ALIAS_MAP: Map<String, Set<String>> = mapOf(
            "bike" to setOf("bicycle", "cycle"),
            "bicycle" to setOf("bike", "cycle"),
            "cycle" to setOf("bike", "bicycle"),

            "car" to setOf("vehicle"),
            "vehicle" to setOf("car"),

            "canteen" to setOf("cafeteria"),
            "cafeteria" to setOf("canteen"),

            "gate" to setOf("entrance"),
            "entrance" to setOf("gate"),

            "parking" to setOf("parking area"),
            "parking area" to setOf("parking")
        )

        /**
         * Common filler words removed during multi-word token overlap analysis.
         */
        private val STOP_WORDS: Set<String> = setOf(
            "my", "the", "a", "an", "at", "in", "on", "of", "to", "for", "is", "where", "did", "i", "spot", "place"
        )

        /**
         * Step 2: Context Signal Weightings.
         * Bounded strictly below semantic step boundaries:
         * MAX_CONTEXT_BONUS (0.045) < ALIAS-to-EXACT delta (0.050).
         */
        const val ROUTE_AFFINITY_BONUS = 0.030f
        const val MAX_RECENCY_BONUS = 0.015f
        const val MAX_CONTEXT_BONUS = ROUTE_AFFINITY_BONUS + MAX_RECENCY_BONUS // 0.045f
    }

    /**
     * Normalizes a query or place name:
     * - lowercase
     * - remove apostrophes (e.g. "bike's" -> "bikes")
     * - strip non-alphanumeric punctuation
     * - collapse repeated spaces and trim
     */
    fun normalize(text: String): String {
        return text.lowercase(Locale.US)
            .replace("'", "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Retrieves and ranks candidate memories matching the given place query.
     *
     * @param query Raw or partially cleaned place query from voice or text input
     * @param memories All saved PlaceMemory items
     * @param currentRouteId Optional active route ID for route affinity weighting
     * @param currentTimeMs Current epoch timestamp for relative recency evaluation
     * @param minScoreThreshold Minimum base score required to include a candidate (default 0.30)
     * @return List of RetrievalCandidate sorted by final score descending
     */
    fun retrieve(
        query: String,
        memories: List<PlaceMemory>,
        currentRouteId: String? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
        minScoreThreshold: Float = 0.30f
    ): List<RetrievalCandidate> {
        val normQuery = normalize(query)
        if (normQuery.isEmpty() || memories.isEmpty()) return emptyList()

        // 1. Evaluate base semantic match for all memories
        val baseCandidates = memories.map { memory ->
            scoreCandidate(normQuery, memory)
        }.filter { it.baseScore >= minScoreThreshold && it.matchType != MatchType.NONE }

        if (baseCandidates.isEmpty()) return emptyList()

        // 2. Compute relative recency range among eligible candidates
        val minCreatedAt = baseCandidates.minOf { it.memory.createdAt }
        val maxCreatedAt = baseCandidates.maxOf { it.memory.createdAt }
        val timeSpan = maxCreatedAt - minCreatedAt

        // 3. Apply bounded contextual signals
        return baseCandidates.map { base ->
            var contextBonus = 0.0f
            val bonusExplanations = mutableListOf<String>()

            // A. Route Affinity (+0.03 if on active route)
            if (!currentRouteId.isNullOrBlank() && base.memory.routeId == currentRouteId) {
                contextBonus += ROUTE_AFFINITY_BONUS
                bonusExplanations.add("route affinity +${String.format(Locale.US, "%.2f", ROUTE_AFFINITY_BONUS)}")
            }

            // B. Weak Recency (up to +0.015 tiebreaker for newer memories)
            if (timeSpan > 0L) {
                val recencyRatio = (base.memory.createdAt - minCreatedAt).toFloat() / timeSpan.toFloat()
                val recencyBonus = recencyRatio * MAX_RECENCY_BONUS
                if (recencyBonus > 0.001f) {
                    contextBonus += recencyBonus
                    bonusExplanations.add("recency +${String.format(Locale.US, "%.3f", recencyBonus)}")
                }
            }

            // Cap total context bonus to safety limit
            val finalBonus = min(contextBonus, MAX_CONTEXT_BONUS)
            val finalScore = base.baseScore + finalBonus

            val finalReason = if (bonusExplanations.isNotEmpty()) {
                "${base.matchReason} (${bonusExplanations.joinToString(", ")})"
            } else {
                base.matchReason
            }

            RetrievalCandidate(
                memory = base.memory,
                score = finalScore,
                matchType = base.matchType,
                matchReason = finalReason,
                baseScore = base.baseScore,
                contextBonus = finalBonus
            )
        }
            .sortedWith(
                compareByDescending<RetrievalCandidate> { it.score }
                    .thenByDescending { it.memory.createdAt }
            )
    }

    /**
     * Finds the single highest-ranking memory candidate with contextual weighting.
     */
    fun findBestMatch(
        query: String,
        memories: List<PlaceMemory>,
        currentRouteId: String? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
        minScoreThreshold: Float = 0.50f
    ): RetrievalCandidate? {
        return retrieve(query, memories, currentRouteId, currentTimeMs, minScoreThreshold).firstOrNull()
    }

    /**
     * Scores a single PlaceMemory against a pre-normalized query string (Step 1 base semantic score).
     */
    fun scoreCandidate(normQuery: String, memory: PlaceMemory): RetrievalCandidate {
        val normName = normalize(memory.name)

        // 1. Exact Normalized Match (Score 1.00)
        if (normQuery == normName) {
            return RetrievalCandidate(
                memory = memory,
                score = 1.00f,
                matchType = MatchType.EXACT,
                matchReason = "Exact normalized name match",
                baseScore = 1.00f,
                contextBonus = 0.0f
            )
        }

        // 2. Direct Controlled Alias Match (Score 0.95)
        if (isAliasMatch(normQuery, normName)) {
            return RetrievalCandidate(
                memory = memory,
                score = 0.95f,
                matchType = MatchType.ALIAS,
                matchReason = "Controlled alias match ('$normQuery' <-> '$normName')",
                baseScore = 0.95f,
                contextBonus = 0.0f
            )
        }

        // Tokenize for multi-word analysis
        val qTokens = normQuery.split(" ").filter { it.isNotBlank() }
        val mTokens = normName.split(" ").filter { it.isNotBlank() }

        // 3. Multi-Word Token Overlap (Only applicable when at least one input is multi-word)
        if (qTokens.size > 1 || mTokens.size > 1) {
            val qSig = qTokens.filter { it !in STOP_WORDS }.ifEmpty { qTokens }
            val mSig = mTokens.filter { it !in STOP_WORDS }.ifEmpty { mTokens }

            val tokenMatchResult = evaluateTokenOverlap(qSig, mSig)
            if (tokenMatchResult != null) {
                return RetrievalCandidate(
                    memory = memory,
                    score = tokenMatchResult.first,
                    matchType = MatchType.TOKEN_OVERLAP,
                    matchReason = tokenMatchResult.second,
                    baseScore = tokenMatchResult.first,
                    contextBonus = 0.0f
                )
            }
        }

        // 4. Conservative Typo Tolerance (Single token or short phrase comparison)
        val typoResult = evaluateTypoMatch(normQuery, normName)
        if (typoResult != null) {
            return RetrievalCandidate(
                memory = memory,
                score = typoResult.first,
                matchType = MatchType.FUZZY_TYPO,
                matchReason = typoResult.second,
                baseScore = typoResult.first,
                contextBonus = 0.0f
            )
        }

        // 5. Substring / Partial Match Fallback
        if (normQuery.length >= 4 && normName.length >= 4) {
            if (normName.contains(normQuery) || normQuery.contains(normName)) {
                val minLen = min(normQuery.length, normName.length).toFloat()
                val maxLen = max(normQuery.length, normName.length).toFloat()
                val subScore = 0.40f + (0.15f * (minLen / maxLen))
                return RetrievalCandidate(
                    memory = memory,
                    score = subScore,
                    matchType = MatchType.PARTIAL,
                    matchReason = "Partial substring match",
                    baseScore = subScore,
                    contextBonus = 0.0f
                )
            }
        }

        // 6. No Match
        return RetrievalCandidate(
            memory = memory,
            score = 0.00f,
            matchType = MatchType.NONE,
            matchReason = "No significant match found",
            baseScore = 0.00f,
            contextBonus = 0.0f
        )
    }

    /**
     * Checks if two normalized terms are direct aliases in the controlled dictionary.
     */
    fun isAliasMatch(term1: String, term2: String): Boolean {
        val aliases1 = ALIAS_MAP[term1]
        if (aliases1 != null && aliases1.contains(term2)) return true

        val aliases2 = ALIAS_MAP[term2]
        if (aliases2 != null && aliases2.contains(term1)) return true

        return false
    }

    /**
     * Evaluates multi-word token overlap between significant query and memory tokens.
     */
    private fun evaluateTokenOverlap(qSig: List<String>, mSig: List<String>): Pair<Float, String>? {
        var matchedQCount = 0
        var matchedMCount = 0

        for (q in qSig) {
            val hasMatch = mSig.any { m ->
                q == m || isAliasMatch(q, m) || isTokenTypoMatch(q, m)
            }
            if (hasMatch) matchedQCount++
        }

        for (m in mSig) {
            val hasMatch = qSig.any { q ->
                m == q || isAliasMatch(m, q) || isTokenTypoMatch(m, q)
            }
            if (hasMatch) matchedMCount++
        }

        if (matchedQCount == 0) return null

        // All significant tokens match on both sides (e.g. "bike parking" vs "My Bike Parking")
        if (matchedQCount == qSig.size && matchedMCount == mSig.size) {
            val allExact = qSig.all { q -> mSig.contains(q) }
            val score = if (allExact) 0.93f else 0.90f
            return Pair(score, "All significant tokens match ($matchedQCount/${qSig.size})")
        }

        // All query tokens found in memory (e.g. "bike parking" in "East Campus Bike Parking")
        if (matchedQCount == qSig.size) {
            val ratio = matchedQCount.toFloat() / max(qSig.size, mSig.size)
            val score = 0.82f + (0.08f * ratio)
            return Pair(score, "Query tokens covered in place name ($matchedQCount/${qSig.size})")
        }

        // All memory tokens covered in query
        if (matchedMCount == mSig.size) {
            val ratio = matchedMCount.toFloat() / max(qSig.size, mSig.size)
            val score = 0.80f + (0.08f * ratio)
            return Pair(score, "Place name tokens covered in query ($matchedMCount/${mSig.size})")
        }

        // Partial token overlap
        val jaccard = (matchedQCount + matchedMCount).toFloat() / (qSig.size + mSig.size)
        if (jaccard >= 0.5f) {
            val score = 0.65f + (0.15f * jaccard)
            return Pair(score, "Partial token overlap ($matchedQCount/${qSig.size})")
        }

        return null
    }

    /**
     * Evaluates typo matching using conservative Damerau-Levenshtein distance.
     */
    private fun evaluateTypoMatch(s1: String, s2: String): Pair<Float, String>? {
        val minLen = min(s1.length, s2.length)
        val maxLen = max(s1.length, s2.length)

        // Strict typo thresholds:
        // <= 3 chars: no typos allowed (avoids "car" matching "cat")
        // 4..6 chars: edit distance <= 1 allowed (e.g. "bkie" -> "bike")
        // >= 7 chars: edit distance <= 2 allowed (e.g. "cafetria" -> "cafeteria")
        val maxAllowedDist = when {
            minLen <= 3 -> 0
            maxLen in 4..6 -> 1
            else -> 2
        }

        if (maxAllowedDist == 0) return null

        val dist = damerauLevenshtein(s1, s2)
        if (dist in 1..maxAllowedDist) {
            val similarity = 1.0f - (dist.toFloat() / maxLen)
            val score = 0.65f + (0.15f * similarity)
            return Pair(score, "Typo match (edit distance $dist, sim ${(similarity * 100).toInt()}%)")
        }

        return null
    }

    private fun isTokenTypoMatch(t1: String, t2: String): Boolean {
        val minLen = min(t1.length, t2.length)
        val maxLen = max(t1.length, t2.length)
        if (minLen <= 3) return false
        val maxDist = if (maxLen in 4..6) 1 else 2
        return damerauLevenshtein(t1, t2) <= maxDist
    }

    /**
     * Computes Damerau-Levenshtein distance with adjacent transposition support.
     */
    fun damerauLevenshtein(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                var minVal = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )

                // Transposition check
                if (i > 1 && j > 1 && s1[i - 1] == s2[j - 2] && s1[i - 2] == s2[j - 1]) {
                    minVal = min(minVal, dp[i - 2][j - 2] + cost)
                }

                dp[i][j] = minVal
            }
        }

        return dp[len1][len2]
    }
}
