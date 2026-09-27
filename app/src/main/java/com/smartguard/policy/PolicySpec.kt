package com.smartguard.policy

/**
 * Structured, machine-checkable policy object.
 *
 * This is the ONLY thing the on-device LLM (or the rule-based fallback) is allowed to
 * produce from a parent's natural-language request. The LLM never enforces anything — the
 * deterministic [PolicyEngine] validates this object and applies it. That separation is
 * what keeps enforcement free of hallucination risk.
 */
data class PolicySpec(
    /** Target role the policy applies to (CHILD/TEEN/ADULT). */
    val targetRole: String = "CHILD",
    /** New screen-time budget in minutes, or null to leave unchanged. */
    val screenTimeMinutes: Int? = null,
    /** App categories to add to the allowed whitelist. */
    val allowCategories: List<String> = emptyList(),
    /** App categories to remove from the allowed whitelist entirely. */
    val blockCategories: List<String> = emptyList(),
    /** Time-window curfews, e.g. "no games after 21:00 on school nights". */
    val curfews: List<CurfewRule> = emptyList(),
    /** Confidence 0..1 that the parse captured the intent. */
    val confidence: Float = 1.0f,
    /** Human-readable echo of what was understood, shown back to the parent. */
    val summary: String = ""
)

data class CurfewRule(
    val category: String,
    /** Hour of day (0-23) after which the category is blocked. */
    val afterHour: Int,
    /** Which days the curfew applies. */
    val days: DayScope = DayScope.DAILY,
    val targetRole: String = "CHILD"
)

enum class DayScope {
    DAILY,
    SCHOOL_NIGHTS, // Sun-Thu evenings
    WEEKENDS;      // Fri-Sat evenings

    companion object {
        fun fromText(text: String): DayScope = when {
            text.contains("school") -> SCHOOL_NIGHTS
            text.contains("weekend") -> WEEKENDS
            else -> DAILY
        }
    }
}
