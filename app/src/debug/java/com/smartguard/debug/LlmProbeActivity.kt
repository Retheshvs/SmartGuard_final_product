package com.smartguard.debug

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.smartguard.policy.llm.LlmEngine
import com.smartguard.policy.llm.LlmPolicyAssistant
import com.smartguard.policy.llm.PolicyAssistant
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY. Runs the real on-device LLM policy translator on prompts passed via adb and
 * logs the results under tag "SG-LLMProbe". Never applies a policy.
 *
 *   adb shell am start -n com.smartguard/.debug.LlmProbeActivity --es prompts "rule one|rule two"
 */
class LlmProbeActivity : AppCompatActivity() {

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply { setPadding(32, 32, 32, 32); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(out) })

        val prompts = (intent.getStringExtra("prompts") ?: "No games after 9pm on school nights for the kids")
            .split('|').map { it.trim() }.filter { it.isNotEmpty() }

        lifecycleScope.launch {
            fun emit(line: String) {
                Log.i(TAG, line)
                out.append(line + "\n\n")
            }

            val status = LlmEngine.ensureLoaded(applicationContext)
            emit("STATUS $status")

            val assistant = LlmPolicyAssistant(applicationContext)
            for (p in prompts) {
                when (val r = assistant.toPolicy(p)) {
                    is PolicyAssistant.PolicyResult.Success -> emit(
                        "PROMPT \"$p\"\n  by=${r.producedBy} latencyMs=${r.latencyMs} fallback=${r.fallbackReason}\n" +
                            "  raw=${r.rawOutput?.replace("\n", " ")?.take(500)}\n  spec=${gson.toJson(r.spec)}"
                    )
                    is PolicyAssistant.PolicyResult.Failure -> emit("PROMPT \"$p\"\n  FAILURE ${r.reason}")
                }
            }
            emit("DONE")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) LlmEngine.release()
    }

    companion object {
        private const val TAG = "SG-LLMProbe"
    }
}
