package com.smartguard.policy

/** Human-readable echo of a [PolicySpec], shown to the parent before they apply it. */
object PolicySummary {

    fun describe(spec: PolicySpec): String {
        val parts = mutableListOf<String>()
        spec.screenTimeMinutes?.let { parts.add("$it min/day screen time") }
        if (spec.allowCategories.isNotEmpty()) parts.add("allow ${spec.allowCategories.joinToString(", ")}")
        if (spec.blockCategories.isNotEmpty()) parts.add("block ${spec.blockCategories.joinToString(", ")}")
        spec.curfews.forEach {
            val days = when (it.days) {
                DayScope.SCHOOL_NIGHTS -> "on school nights"
                DayScope.WEEKENDS -> "on weekends"
                DayScope.DAILY -> "daily"
            }
            parts.add("no ${it.category} after %02d:00 %s".format(it.afterHour, days))
        }
        return "For ${spec.targetRole.lowercase()}: " + parts.joinToString("; ")
    }
}
