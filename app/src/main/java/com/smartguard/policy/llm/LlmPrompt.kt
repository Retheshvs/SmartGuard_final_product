package com.smartguard.policy.llm

/**
 * Prompt construction for the on-device policy translator. Pure Kotlin so it is unit-testable.
 *
 * The whole instruction (schema + few-shot examples + request) goes into a single user turn,
 * wrapped by the model family's chat template. Few-shot examples pin the exact JSON shape.
 */
object LlmPrompt {

    /** Chat-template markers for a model family (null fields = no wrapping). */
    data class Template(
        val family: String,
        val userPrefix: String,
        val userSuffix: String,
        val modelPrefix: String,
        val modelSuffix: String
    )

    val QWEN = Template(
        family = "Qwen (ChatML)",
        userPrefix = "<|im_start|>user\n",
        userSuffix = "<|im_end|>\n",
        modelPrefix = "<|im_start|>assistant\n",
        modelSuffix = "<|im_end|>\n"
    )

    val GEMMA = Template(
        family = "Gemma",
        userPrefix = "<start_of_turn>user\n",
        userSuffix = "<end_of_turn>\n",
        modelPrefix = "<start_of_turn>model\n",
        modelSuffix = "<end_of_turn>\n"
    )

    fun templateFor(modelFileName: String): Template? {
        val n = modelFileName.lowercase()
        return when {
            n.contains("qwen") -> QWEN
            n.contains("gemma") -> GEMMA
            else -> null
        }
    }

    private const val MAX_REQUEST_CHARS = 300

    fun instruction(request: String): String {
        val clean = request.replace(Regex("""\s+"""), " ").trim().take(MAX_REQUEST_CHARS)
        return """
You are the policy translator for SmartGuard, an on-device parental-control app.
Convert the parent's request into ONE JSON object and output nothing else.

JSON fields:
- "targetRole": "CHILD", "TEEN" or "ADULT" (kids, son, daughter, child = CHILD; teen, teenager = TEEN). Default "CHILD".
- "screenTimeMinutes": daily screen-time budget in minutes as an integer, or null if not mentioned.
- "allowCategories": app categories to allow.
- "blockCategories": app categories to block completely.
- "curfews": for rules like "no X after <time>": list of {"category": ..., "afterHour": 0-23 on a 24-hour clock (9pm = 21), "days": "DAILY" or "SCHOOL_NIGHTS" or "WEEKENDS"}.
- "summary": one short sentence restating the rule.
Valid categories: games, social, video, browser, education, utilities.
Use [] or null for anything not mentioned. Never invent rules that were not asked for.

Examples:
Request: No games after 9pm on school nights
JSON: {"targetRole":"CHILD","screenTimeMinutes":null,"allowCategories":[],"blockCategories":[],"curfews":[{"category":"games","afterHour":21,"days":"SCHOOL_NIGHTS"}],"summary":"No games after 21:00 on school nights."}

Request: My teenager can have 2 hours a day but no social media
JSON: {"targetRole":"TEEN","screenTimeMinutes":120,"allowCategories":[],"blockCategories":["social"],"curfews":[],"summary":"Teen gets 120 minutes a day with social apps blocked."}

Request: Let the kids use YouTube and learning apps for 45 minutes
JSON: {"targetRole":"CHILD","screenTimeMinutes":45,"allowCategories":["video","education"],"blockCategories":[],"curfews":[],"summary":"Kids get 45 minutes a day with video and education apps allowed."}

Request: $clean
JSON:
""".trim()
    }

    /** Full prompt with template markers inline (used when the runtime can't take templates). */
    fun wrapped(template: Template?, request: String): String {
        val body = instruction(request)
        return if (template == null) body else template.userPrefix + body + template.userSuffix + template.modelPrefix
    }
}
