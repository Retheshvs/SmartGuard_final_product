package com.smartguard.policy.llm

import com.smartguard.policy.PolicySpec

/**
 * Translates a parent's natural-language request into a structured [PolicySpec].
 *
 * Implementations:
 *  - [LlmPolicyAssistant]: on-device LLM (Google AI Edge / MediaPipe LLM Inference API).
 *  - [RuleBasedPolicyAssistant]: fully offline keyword translator, used when no model is present
 *    or the model's output can't be turned into a valid policy.
 *
 * The assistant only ever produces a policy object; it never enforces anything.
 */
interface PolicyAssistant {

    val backendName: String

    suspend fun toPolicy(naturalLanguage: String): PolicyResult

    sealed class PolicyResult {
        data class Success(
            val spec: PolicySpec,
            /** Which backend actually produced this policy (shown to the parent). */
            val producedBy: String = "",
            val latencyMs: Long = 0,
            /** Raw model text, for transparency/debugging (null for rule-based). */
            val rawOutput: String? = null,
            /** Set when the LLM was tried but we fell back to rule-based. */
            val fallbackReason: String? = null
        ) : PolicyResult()

        data class Failure(val reason: String) : PolicyResult()
    }
}
