package com.smartguard.policy

import com.smartguard.data.repository.ProfileRepository

/**
 * Deterministic policy engine. Validates a [PolicySpec] (produced by the LLM/rule-based
 * assistant) and applies it to the stored profiles + [PolicyStore]. Enforcement decisions live
 * here and in the AccessibilityService — never in the language model.
 */
class PolicyEngine(
    private val repository: ProfileRepository,
    private val policyStore: PolicyStore
) {

    data class ApplyResult(
        val ok: Boolean,
        val message: String,
        val affectedProfiles: Int = 0
    )

    /** Validates ranges and known categories before anything is persisted. */
    fun validate(spec: PolicySpec): String? {
        spec.screenTimeMinutes?.let {
            if (it !in 0..1440) return "Screen time must be between 0 and 1440 minutes."
        }
        val allCats = spec.allowCategories + spec.blockCategories + spec.curfews.map { it.category }
        val unknown = allCats.filter { it.lowercase() !in AppCategories.knownCategories }
        if (unknown.isNotEmpty()) return "Unknown app categories: ${unknown.joinToString(", ")}."
        spec.curfews.forEach {
            if (it.afterHour !in 0..23) return "Curfew hour must be 0-23."
        }
        return null
    }

    suspend fun apply(spec: PolicySpec): ApplyResult {
        validate(spec)?.let { return ApplyResult(false, it) }

        val targets = repository.getAllProfiles().filter {
            it.role.equals(spec.targetRole, ignoreCase = true)
        }

        if (targets.isEmpty() && spec.curfews.isEmpty()) {
            return ApplyResult(false, "No ${spec.targetRole} profile exists yet to apply this to.")
        }

        var affected = 0
        for (profile in targets) {
            // Restricted-apps model: "block X" adds X's apps to the restricted list, "allow X" removes them.
            val restricted = KidsPolicy.parse(profile.blockedPackagesJson).toMutableSet()
            spec.blockCategories.forEach { restricted.addAll(AppCategories.packagesFor(it)) }
            spec.allowCategories.forEach { restricted.removeAll(AppCategories.packagesFor(it).toSet()) }

            val newBudget = spec.screenTimeMinutes ?: profile.screenTimeBudgetMinutes

            val updated = profile.copy(
                screenTimeBudgetMinutes = newBudget,
                blockedPackagesJson = KidsPolicy.toJson(restricted)
            )
            repository.updateProfile(updated)
            affected++
        }

        // Curfews are stored globally by role, enforced live by the AccessibilityService.
        policyStore.addCurfews(spec.curfews)

        return ApplyResult(
            ok = true,
            message = "Policy applied. ${spec.summary}",
            affectedProfiles = affected
        )
    }

}
