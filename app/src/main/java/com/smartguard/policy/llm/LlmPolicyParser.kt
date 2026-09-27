package com.smartguard.policy.llm

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartguard.policy.AppCategories
import com.smartguard.policy.CurfewRule
import com.smartguard.policy.DayScope
import com.smartguard.policy.PolicySpec
import com.smartguard.policy.PolicySummary

/**
 * Turns raw LLM text into a sanitized [PolicySpec], or null if nothing usable came back.
 *
 * The model's output is treated as untrusted: it is parsed leniently (code fences, stray prose,
 * "21:00" vs 21, synonyms like "social media"), and anything outside the allowed vocabulary is
 * dropped. The deterministic PolicyEngine then validates again before anything is enforced.
 */
object LlmPolicyParser {

    private val ROLES = setOf("CHILD", "TEEN", "ADULT")

    private val CATEGORY_SYNONYMS = mapOf(
        "game" to "games", "gaming" to "games",
        "social media" to "social", "social_media" to "social", "socials" to "social", "messaging" to "social",
        "videos" to "video", "youtube" to "video", "streaming" to "video",
        "browsers" to "browser", "web" to "browser", "internet" to "browser",
        "educational" to "education", "learning" to "education", "school" to "education",
        "utility" to "utilities", "tools" to "utilities"
    )

    fun parse(raw: String): PolicySpec? {
        val json = extractJsonObject(raw) ?: return null
        val obj = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null

        val role = obj.string("targetRole")?.uppercase()?.takeIf { it in ROLES } ?: "CHILD"
        val minutes = obj.int("screenTimeMinutes")?.takeIf { it in 1..1440 }
        val allow = categories(obj.array("allowCategories"))
        val block = categories(obj.array("blockCategories")).filterNot { it in allow }

        val curfews = obj.array("curfews")?.mapNotNull { el ->
            val c = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val category = normalizeCategory(c.string("category")) ?: return@mapNotNull null
            val hour = c.hour("afterHour") ?: return@mapNotNull null
            CurfewRule(category, hour, dayScope(c.string("days")), role)
        }.orEmpty()

        // A time-window rule ("no games after 9pm") is more specific than a full block. Small models
        // sometimes emit both for the same category, which would wrongly block it all day.
        val curfewed = curfews.map { it.category }.toSet()
        val fullBlock = block.filterNot { it in curfewed }

        if (minutes == null && allow.isEmpty() && fullBlock.isEmpty() && curfews.isEmpty()) return null

        val spec = PolicySpec(
            targetRole = role,
            screenTimeMinutes = minutes,
            allowCategories = allow,
            blockCategories = fullBlock,
            curfews = curfews,
            confidence = 0.9f
        )
        // Always show the parent OUR deterministic restatement, not free text from the model.
        return spec.copy(summary = PolicySummary.describe(spec))
    }

    /** Finds the first balanced {...} object, ignoring braces inside strings and any prose/fences. */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun categories(arr: JsonArray?): List<String> =
        arr?.mapNotNull { el -> if (el.isJsonPrimitive) normalizeCategory(el.asString) else null }
            ?.distinct()
            .orEmpty()

    private fun normalizeCategory(value: String?): String? {
        val v = value?.trim()?.lowercase() ?: return null
        val mapped = CATEGORY_SYNONYMS[v] ?: v
        return mapped.takeIf { it in AppCategories.knownCategories }
    }

    private fun dayScope(value: String?): DayScope {
        val v = value?.lowercase()?.replace('_', ' ') ?: return DayScope.DAILY
        return when {
            v.contains("school") || v.contains("weeknight") -> DayScope.SCHOOL_NIGHTS
            v.contains("weekend") -> DayScope.WEEKENDS
            else -> DayScope.DAILY
        }
    }

    private fun JsonObject.field(name: String): JsonElement? =
        get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.string(name: String): String? =
        field(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.array(name: String): JsonArray? =
        field(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.int(name: String): Int? {
        val p = field(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> p.asDouble.toInt()
            else -> Regex("""\d+""").find(p.asString)?.value?.toIntOrNull()
        }
    }

    /** Accepts 21, 21.0, "21", "21:00", "9pm", "9 PM". */
    private fun JsonObject.hour(name: String): Int? {
        val p = field(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (p.isNumber) return p.asDouble.toInt().takeIf { it in 0..23 }
        val s = p.asString.trim().lowercase()
        val m = Regex("""^(\d{1,2})(?::\d{2})?\s*(am|pm)?$""").find(s) ?: return null
        var h = m.groupValues[1].toInt()
        when (m.groupValues[2]) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        return h.takeIf { it in 0..23 }
    }
}
