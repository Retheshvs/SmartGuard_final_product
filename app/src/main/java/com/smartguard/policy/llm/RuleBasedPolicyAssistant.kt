package com.smartguard.policy.llm

import com.smartguard.policy.AppCategories
import com.smartguard.policy.CurfewRule
import com.smartguard.policy.DayScope
import com.smartguard.policy.PolicySpec
import com.smartguard.policy.PolicySummary

/**
 * Deterministic, on-device natural-language -> PolicySpec translator.
 *
 * This is the always-available fallback for the on-device LLM Policy Assistant. It uses keyword
 * and pattern extraction (no network, no model file) so the feature works in every demo. When a
 * Gemma 2B backend is bundled, it replaces this class but produces the same [PolicySpec] contract,
 * which the deterministic PolicyEngine then validates and applies.
 */
class RuleBasedPolicyAssistant : PolicyAssistant {

    override val backendName: String = "Rule-based (offline)"

    private val timeRegex = Regex("""(\d{1,2})\s*(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE)

    override suspend fun toPolicy(naturalLanguage: String): PolicyAssistant.PolicyResult {
        val text = naturalLanguage.lowercase().trim()
        if (text.isEmpty()) {
            return PolicyAssistant.PolicyResult.Failure("Empty request.")
        }

        val targetRole = extractRole(text)
        val screenTime = extractScreenTime(text)
        val mentionedCategories = AppCategories.knownCategories.filter { text.contains(it) }

        val isBlocking = containsAny(text, "no ", "block", "ban", "disable", "not allowed", "restrict", "after")
        val isAllowing = containsAny(text, "allow", "enable", "let ", "permit", "can use")

        val allowCategories = mutableListOf<String>()
        val blockCategories = mutableListOf<String>()
        val curfews = mutableListOf<CurfewRule>()

        val curfewHour = extractCurfewHour(text)
        val dayScope = DayScope.fromText(text)

        for (cat in mentionedCategories) {
            when {
                curfewHour != null && (text.contains("after") || text.contains("past") || text.contains("by ")) -> {
                    curfews.add(CurfewRule(cat, curfewHour, dayScope, targetRole))
                }
                isAllowing && !isBlocking -> allowCategories.add(cat)
                else -> blockCategories.add(cat)
            }
        }

        // If a bare curfew hour was given with a category but no explicit direction, still make a curfew.
        if (curfews.isEmpty() && curfewHour != null && mentionedCategories.isNotEmpty()) {
            mentionedCategories.forEach { curfews.add(CurfewRule(it, curfewHour, dayScope, targetRole)) }
        }

        val confidence = computeConfidence(screenTime, allowCategories, blockCategories, curfews)
        if (confidence == 0f) {
            return PolicyAssistant.PolicyResult.Failure(
                "Couldn't understand a concrete rule. Try: \"no games after 9pm on school nights\" " +
                    "or \"give the kids 45 minutes and allow education apps\"."
            )
        }

        val spec = PolicySpec(
            targetRole = targetRole,
            screenTimeMinutes = screenTime,
            allowCategories = allowCategories.distinct(),
            blockCategories = blockCategories.distinct(),
            curfews = curfews,
            confidence = confidence
        )
        return PolicyAssistant.PolicyResult.Success(
            spec = spec.copy(summary = PolicySummary.describe(spec)),
            producedBy = backendName
        )
    }

    private fun extractRole(text: String): String = when {
        text.contains("teen") -> "TEEN"
        text.contains("adult") || text.contains("parent") -> "ADULT"
        else -> "CHILD" // "kid", "child", "son", "daughter", default
    }

    private fun extractScreenTime(text: String): Int? {
        // "45 minutes", "2 hours", "1 hour 30", "90 min"
        val hourMatch = Regex("""(\d+)\s*(?:hours?|hrs?|h)\b""").find(text)
        val minMatch = Regex("""(\d+)\s*(?:minutes?|mins?|m)\b""").find(text)
        if (hourMatch == null && minMatch == null) return null
        val hours = hourMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + mins
        return if (total in 1..1440) total else null
    }

    private fun extractCurfewHour(text: String): Int? {
        // Look for a time near an "after/past/by" keyword.
        val anchor = listOf("after", "past", "by").firstNotNullOfOrNull { kw ->
            val idx = text.indexOf(kw)
            if (idx >= 0) text.substring(idx) else null
        } ?: return null

        val m = timeRegex.find(anchor) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val meridiem = m.groupValues[3].lowercase()
        when (meridiem) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
            else -> {
                // No am/pm: assume evening curfews for small numbers (e.g. "after 9" -> 21:00).
                if (hour in 1..11) hour += 12
            }
        }
        return hour.coerceIn(0, 23)
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    private fun computeConfidence(
        screenTime: Int?,
        allow: List<String>,
        block: List<String>,
        curfews: List<CurfewRule>
    ): Float {
        var signals = 0
        if (screenTime != null) signals++
        if (allow.isNotEmpty()) signals++
        if (block.isNotEmpty()) signals++
        if (curfews.isNotEmpty()) signals++
        return when (signals) {
            0 -> 0f
            1 -> 0.75f
            else -> 0.95f
        }
    }

}
