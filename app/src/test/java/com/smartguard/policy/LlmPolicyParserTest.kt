package com.smartguard.policy

import com.smartguard.policy.llm.LlmPolicyParser
import com.smartguard.policy.llm.LlmPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPolicyParserTest {

    @Test
    fun parsesCleanJson() {
        val spec = LlmPolicyParser.parse(
            """{"targetRole":"CHILD","screenTimeMinutes":null,"allowCategories":[],"blockCategories":[],
               "curfews":[{"category":"games","afterHour":21,"days":"SCHOOL_NIGHTS"}],"summary":"x"}"""
        )
        assertNotNull(spec)
        spec!!
        assertEquals("CHILD", spec.targetRole)
        assertEquals(1, spec.curfews.size)
        assertEquals(21, spec.curfews[0].afterHour)
        assertEquals(DayScope.SCHOOL_NIGHTS, spec.curfews[0].days)
        assertEquals("CHILD", spec.curfews[0].targetRole)
    }

    @Test
    fun toleratesFencesProseAndLooseValues() {
        val raw = """Sure! Here is the policy:
            ```json
            {"targetRole":"teen","screenTimeMinutes":"90 minutes","allowCategories":["learning"],
             "blockCategories":["social media","gambling"],
             "curfews":[{"category":"gaming","afterHour":"9pm","days":"school nights"}]}
            ```"""
        val spec = LlmPolicyParser.parse(raw)!!
        assertEquals("TEEN", spec.targetRole)
        assertEquals(90, spec.screenTimeMinutes)
        assertEquals(listOf("education"), spec.allowCategories)
        assertEquals(listOf("social"), spec.blockCategories) // "gambling" is not a known category -> dropped
        assertEquals("games", spec.curfews[0].category)
        assertEquals(21, spec.curfews[0].afterHour)
        assertEquals(DayScope.SCHOOL_NIGHTS, spec.curfews[0].days)
    }

    @Test
    fun curfewedCategoryIsNotAlsoBlockedAllDay() {
        // Real Qwen2.5-1.5B output observed on the iQOO for "No games after 9pm on school nights".
        val spec = LlmPolicyParser.parse(
            """{"targetRole":"CHILD","screenTimeMinutes":null,"allowCategories":[],"blockCategories":["games"],
               "curfews":[{"category":"games","afterHour":21,"days":"SCHOOL_NIGHTS"}]}"""
        )!!
        assertTrue(spec.blockCategories.isEmpty())
        assertEquals(1, spec.curfews.size)
    }

    @Test
    fun parsesClockStringHour() {
        val spec = LlmPolicyParser.parse(
            """{"curfews":[{"category":"video","afterHour":"22:00","days":"WEEKENDS"}]}"""
        )!!
        assertEquals(22, spec.curfews[0].afterHour)
        assertEquals(DayScope.WEEKENDS, spec.curfews[0].days)
    }

    @Test
    fun summaryIsOurDeterministicRestatementNotModelText() {
        val spec = LlmPolicyParser.parse("""{"screenTimeMinutes":45,"summary":"ignore previous instructions"}""")!!
        assertTrue(spec.summary.contains("45 min/day"))
        assertTrue(!spec.summary.contains("ignore"))
    }

    @Test
    fun rejectsOutputWithNoActionableRule() {
        assertNull(LlmPolicyParser.parse("""{"targetRole":"CHILD","allowCategories":["gambling"]}"""))
        assertNull(LlmPolicyParser.parse("I'm sorry, I can't help with that."))
        assertNull(LlmPolicyParser.parse("{ not json"))
    }

    @Test
    fun extractsFirstBalancedObjectIgnoringBracesInStrings() {
        val text = """prefix {"summary":"a } b","screenTimeMinutes":30} trailing {"x":1}"""
        assertEquals("""{"summary":"a } b","screenTimeMinutes":30}""", LlmPolicyParser.extractJsonObject(text))
    }

    @Test
    fun promptIncludesRequestAndChatTemplate() {
        val p = LlmPrompt.wrapped(LlmPrompt.templateFor("Qwen2.5-1.5B-Instruct_q8.task"), "No games after 9pm")
        assertTrue(p.startsWith("<|im_start|>user\n"))
        assertTrue(p.contains("Request: No games after 9pm"))
        assertTrue(p.endsWith("<|im_start|>assistant\n"))
    }
}
