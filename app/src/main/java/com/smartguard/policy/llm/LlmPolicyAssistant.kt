package com.smartguard.policy.llm

import android.content.Context

/**
 * On-device LLM policy translator: natural language -> PolicySpec JSON, via [LlmEngine].
 *
 * The LLM only translates. Its output is parsed and sanitized by [LlmPolicyParser] and validated
 * again by the deterministic PolicyEngine before anything is enforced. If no model is installed,
 * or the model's answer can't be turned into a valid policy, the request is handled by the
 * rule-based translator and the result says so explicitly (no silent fallback).
 */
class LlmPolicyAssistant(private val context: Context) : PolicyAssistant {

    private val fallback = RuleBasedPolicyAssistant()

    override val backendName: String
        get() = when (val s = LlmEngine.status) {
            is LlmEngine.Status.Ready -> "${s.modelName} (on-device LLM)"
            else -> fallback.backendName
        }

    override suspend fun toPolicy(naturalLanguage: String): PolicyAssistant.PolicyResult {
        val status = LlmEngine.ensureLoaded(context)
        if (status !is LlmEngine.Status.Ready) {
            return withFallback(naturalLanguage, reason = describe(status))
        }

        val generation = LlmEngine.generate(naturalLanguage)
            ?: return withFallback(naturalLanguage, reason = "LLM generation failed")

        val spec = LlmPolicyParser.parse(generation.text)
            ?: return withFallback(naturalLanguage, reason = "LLM output was not a usable policy", raw = generation.text)

        return PolicyAssistant.PolicyResult.Success(
            spec = spec,
            producedBy = "${status.modelName} · on-device",
            latencyMs = generation.latencyMs,
            rawOutput = generation.text
        )
    }

    private suspend fun withFallback(text: String, reason: String, raw: String? = null): PolicyAssistant.PolicyResult =
        when (val r = fallback.toPolicy(text)) {
            is PolicyAssistant.PolicyResult.Success -> r.copy(fallbackReason = reason, rawOutput = raw)
            is PolicyAssistant.PolicyResult.Failure -> r
        }

    private fun describe(status: LlmEngine.Status): String = when (status) {
        is LlmEngine.Status.NotFound -> "no LLM model installed"
        is LlmEngine.Status.Failed -> "LLM failed to load: ${status.reason}"
        LlmEngine.Status.NotLoaded -> "LLM not loaded"
        is LlmEngine.Status.Ready -> "ready"
    }
}
