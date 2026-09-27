package com.smartguard.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.databinding.ActivityPolicyAssistantBinding
import com.smartguard.policy.PolicyEngine
import com.smartguard.policy.PolicySpec
import com.smartguard.policy.PolicyStore
import com.smartguard.policy.UserRole
import com.smartguard.policy.llm.LlmEngine
import com.smartguard.policy.llm.LlmPolicyAssistant
import com.smartguard.policy.llm.PolicyAssistant
import kotlinx.coroutines.launch

/**
 * Parent-facing natural-language policy screen:
 * text -> on-device LLM -> structured PolicySpec -> validate -> deterministic PolicyEngine applies it.
 * Parent-gated: only usable in Adult mode.
 */
class PolicyAssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPolicyAssistantBinding

    /** Applying a rule is a settings change: needs a fresh parent face check. */
    private val parentAuth = ParentAuthGate(this)
    private lateinit var assistant: PolicyAssistant
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    private var currentSpec: PolicySpec? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPolicyAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Parent gating: only an active Adult session may edit policies.
        if (SmartGuardApp.instance.sessionState.value.activeRole != UserRole.ADULT) {
            Toast.makeText(this, "Parent gated: verify as an Adult to edit policies.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        finishWhenParentLeaves()
        assistant = LlmPolicyAssistant(applicationContext)
        setupListeners()
        warmUpModel()
    }

    /** Loads the model as soon as the screen opens so the first request is fast. */
    private fun warmUpModel() {
        setBusy(true, "Loading on-device language model…")
        showModelStatus(LlmEngine.status)
        lifecycleScope.launch {
            val status = LlmEngine.ensureLoaded(applicationContext)
            setBusy(false)
            showModelStatus(status)
        }
    }

    private fun showModelStatus(status: LlmEngine.Status) {
        val (text, color) = when (status) {
            is LlmEngine.Status.Ready ->
                "On-device LLM ready · ${status.modelName} · loaded in %.1fs · no internet used".format(status.loadMs / 1000f) to
                    R.color.accent_emerald
            is LlmEngine.Status.NotFound ->
                "No LLM model installed — using the offline rule-based translator.\nPush a .task model to: ${status.searched.first()}" to
                    R.color.accent_amber
            is LlmEngine.Status.Failed ->
                "LLM failed to load (${status.reason}) — using the rule-based translator." to R.color.accent_rose
            LlmEngine.Status.NotLoaded ->
                "Preparing on-device language model…" to R.color.text_secondary
        }
        binding.tvBackend.text = text
        binding.tvBackend.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun setupListeners() {
        binding.btnExample1.setOnClickListener {
            binding.etPrompt.setText("No games after 9pm on school nights for the kids")
        }
        binding.btnExample2.setOnClickListener {
            binding.etPrompt.setText("Give the kids 45 minutes and allow education apps")
        }
        binding.btnGenerate.setOnClickListener { generate() }
        binding.btnApply.setOnClickListener { parentAuth.require("Apply this rule") { apply() } }
    }

    private fun setBusy(isBusy: Boolean, label: String = "Thinking on-device…") {
        busy = isBusy
        binding.rowBusy.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.tvBusy.text = label
        binding.btnGenerate.isEnabled = !isBusy
    }

    private fun generate() {
        if (busy) return
        val text = binding.etPrompt.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type a rule first.", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true, "Thinking on-device…")
        binding.cardResult.visibility = View.GONE
        lifecycleScope.launch {
            val result = assistant.toPolicy(text)
            setBusy(false)
            showModelStatus(LlmEngine.status)
            when (result) {
                is PolicyAssistant.PolicyResult.Success -> {
                    currentSpec = result.spec
                    binding.tvSummary.text = result.spec.summary
                    binding.tvGenInfo.text = when {
                        result.fallbackReason != null ->
                            "Produced by ${result.producedBy} (fallback: ${result.fallbackReason})"
                        result.latencyMs > 0 ->
                            "Generated by ${result.producedBy} in %.1fs".format(result.latencyMs / 1000f)
                        else -> "Produced by ${result.producedBy}"
                    }
                    binding.tvJson.text = prettyGson.toJson(result.spec)
                    binding.cardResult.visibility = View.VISIBLE
                }
                is PolicyAssistant.PolicyResult.Failure -> {
                    currentSpec = null
                    Toast.makeText(this@PolicyAssistantActivity, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun apply() {
        val spec = currentSpec ?: return
        val engine = PolicyEngine(
            repository = SmartGuardApp.instance.profileRepository,
            policyStore = PolicyStore(applicationContext)
        )
        lifecycleScope.launch {
            val result = engine.apply(spec)
            Toast.makeText(this@PolicyAssistantActivity, result.message, Toast.LENGTH_LONG).show()
            if (result.ok) finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Free ~1.6 GB when the assistant closes; it reloads in a few seconds next time.
        if (isFinishing) LlmEngine.release()
    }
}
