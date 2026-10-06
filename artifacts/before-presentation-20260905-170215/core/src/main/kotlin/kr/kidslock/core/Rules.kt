package kr.kidslock.core

import java.time.LocalDate

data class Rule(
    val packageName: String,
    val label: String,
    val limitSeconds: Long = 3600,
    val graceSeconds: Long = 300,
    val parentApproved: Boolean = false,
    val childApproved: Boolean = false
) {
    val active: Boolean get() = parentApproved && childApproved && limitSeconds > 0 && graceSeconds > 0
}

data class RuleProgress(
    val day: String = "",
    val warningShown: Boolean = false,
    val graceShown: Boolean = false,
    val violationRecorded: Boolean = false,
    val violationDays: List<String> = emptyList(),
    val blockedUntil: Long = 0,
    val graceStartSeconds: Long = -1
)
enum class RuleEvent { NONE, WARNING, GRACE, FIRST_VIOLATION, BLOCK }
data class RuleDecision(val progress: RuleProgress, val event: RuleEvent, val blocked: Boolean)

/** One violation per calendar day, counted in a rolling seven-day window. Grace is foreground use. */
object RuleEngine {
    fun evaluate(rule: Rule, previous: RuleProgress, day: LocalDate, usedSeconds: Long, now: Long): RuleDecision {
        if (!rule.active) return RuleDecision(previous.copy(blockedUntil = 0), RuleEvent.NONE, false)
        val validDays = previous.violationDays.filter {
            runCatching { LocalDate.parse(it) }.getOrNull()?.let { d -> d <= day && d >= day.minusDays(6) } == true
        }.distinct()
        var state = if (previous.day == day.toString()) previous.copy(violationDays = validDays)
        else RuleProgress(day = day.toString(), violationDays = validDays, blockedUntil = previous.blockedUntil)
        if (state.blockedUntil > now) return RuleDecision(state, RuleEvent.NONE, true)
        if (state.violationRecorded) return RuleDecision(state, RuleEvent.NONE, false)
        // Deliver warning and grace as separate steps even when the service reconnects above the limit.
        if (!state.warningShown && usedSeconds >= (rule.limitSeconds - 300).coerceAtLeast(0)) {
            state = state.copy(warningShown = true)
            return RuleDecision(state, RuleEvent.WARNING, false)
        }
        if (!state.graceShown && usedSeconds >= rule.limitSeconds) {
            state = state.copy(graceShown = true, graceStartSeconds = usedSeconds)
            return RuleDecision(state, RuleEvent.GRACE, false)
        }
        if (usedSeconds >= maxOf(rule.limitSeconds, state.graceStartSeconds) + rule.graceSeconds) {
            val days = (validDays + day.toString()).distinct()
            val duration = when (days.size) { 1 -> 0L; 2 -> 10 * 60_000L; else -> 30 * 60_000L }
            state = state.copy(violationRecorded = true, violationDays = days, blockedUntil = if (duration > 0) now + duration else 0)
            return RuleDecision(state, if (duration == 0L) RuleEvent.FIRST_VIOLATION else RuleEvent.BLOCK, duration > 0)
        }
        return RuleDecision(state, RuleEvent.NONE, false)
    }
}

/** Never bills a background, locked, unknown, or long unobserved interval as foreground usage. */
class UsageClock(private val maxGapMillis: Long = 5000) {
    private var lastApp: String? = null
    private var lastTime = 0L
    fun tick(app: String?, interactive: Boolean, elapsed: Long): Pair<String, Long>? {
        val delta = elapsed - lastTime
        val result = if (interactive && app != null && app == lastApp && delta in 1..maxGapMillis) app to delta else null
        lastApp = if (interactive) app else null
        lastTime = elapsed
        return result
    }
}
