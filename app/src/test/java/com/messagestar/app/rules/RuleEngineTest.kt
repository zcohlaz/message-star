package com.messagestar.app.rules

import com.messagestar.app.data.Rule
import com.messagestar.app.data.RuleType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    @Test
    fun normalizesMainlandPhoneFormats() {
        assertTrue(RuleEngine.normalizePhone("+86 138-0013-8000") == "13800138000")
        assertTrue(RuleEngine.normalizePhone("86(138)00138000") == "13800138000")
        assertTrue(RuleEngine.normalizePhone("106910082527830") == "106910082527830")
    }

    @Test
    fun platformRuleRequiresAllKeywords() {
        val rule = Rule("1", RuleType.PLATFORM, name = "辽宁交警", keywords = listOf("【辽宁交警】", "未按规定停放", "请立即驶离"))
        assertTrue(RuleEngine.match(listOf(rule), "1069", "【辽宁交警】车辆未按规定停放，请立即驶离").matched)
        assertFalse(RuleEngine.match(listOf(rule), "1069", "【辽宁交警】车辆未按规定停放").matched)
    }

    @Test
    fun phoneRuleIsExact() {
        val rule = Rule("1", RuleType.PHONE, phoneNumber = "13800138000")
        assertTrue(RuleEngine.match(listOf(rule), "+8613800138000", "hello").matched)
        assertFalse(RuleEngine.match(listOf(rule), "13800138001", "hello").matched)
    }
}
