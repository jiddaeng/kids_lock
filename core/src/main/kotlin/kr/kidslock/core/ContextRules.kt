package kr.kidslock.core

object ContextRules {
    fun inStudyWindow(rule: Rule, minute: Int): Boolean {
        val start = rule.studyStartMinute; val end = rule.studyEndMinute
        if (!rule.active || start !in 0..1439 || end !in 0..1439 || start == end) return false
        return if (start < end) minute in start until end else minute >= start || minute < end
    }
    fun riskApplies(rule: Rule, analysis: Analysis?): Boolean = rule.active && rule.protectRisk && analysis?.risk == Risk.POSSIBLE
}
