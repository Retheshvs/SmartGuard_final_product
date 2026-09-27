package com.smartguard.policy

import com.smartguard.policy.llm.PolicyAssistant
import com.smartguard.policy.llm.RuleBasedPolicyAssistant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedPolicyAssistantTest {

    private val assistant = RuleBasedPolicyAssistant()

    private fun parse(text: String): PolicySpec {
        val r = runBlocking { assistant.toPolicy(text) }
        assertTrue("Expected success for: $text", r is PolicyAssistant.PolicyResult.Success)
        return (r as PolicyAssistant.PolicyResult.Success).spec
    }

    @Test
    fun parsesGamesCurfewOnSchoolNights() {
        val spec = parse("No games after 9pm on school nights for the kids")
        assertEquals("CHILD", spec.targetRole)
        assertEquals(1, spec.curfews.size)
        val rule = spec.curfews.first()
        assertEquals("games", rule.category)
        assertEquals(21, rule.afterHour)
        assertEquals(DayScope.SCHOOL_NIGHTS, rule.days)
    }

    @Test
    fun parsesScreenTimeAndAllowEducation() {
        val spec = parse("Give the kids 45 minutes and allow education apps")
        assertEquals(45, spec.screenTimeMinutes)
        assertTrue(spec.allowCategories.contains("education"))
    }

    @Test
    fun parsesHoursToMinutes() {
        val spec = parse("Allow the teen 2 hours of screen time")
        assertEquals("TEEN", spec.targetRole)
        assertEquals(120, spec.screenTimeMinutes)
    }

    @Test
    fun gibberishFails() {
        val r = runBlocking { assistant.toPolicy("asdfghjkl") }
        assertTrue(r is PolicyAssistant.PolicyResult.Failure)
    }
}
