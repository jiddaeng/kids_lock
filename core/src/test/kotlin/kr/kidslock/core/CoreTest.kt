package kr.kidslock.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CoreTest {
    private val day = LocalDate.of(2026, 9, 5)
    private val rule = Rule("game", "게임", 600, 60, true, true)
    private fun expired(prior: List<String> = emptyList()) = RuleProgress(day.toString(), true, true, false, prior)

    @Test fun noRestrictionWithoutBothApprovals() {
        for (candidate in listOf(rule.copy(parentApproved = false), rule.copy(childApproved = false))) {
            val result = RuleEngine.evaluate(candidate, expired(), day, 9999, 1000)
            assertFalse(result.blocked)
            assertEquals(RuleEvent.NONE, result.event)
        }
    }
    @Test fun firstViolationDoesNotBlockAndIsCountedOnlyOnce() {
        val result = RuleEngine.evaluate(rule, expired(), day, 660, 1000)
        assertEquals(RuleEvent.FIRST_VIOLATION, result.event)
        assertFalse(result.blocked)
        val again = RuleEngine.evaluate(rule, result.progress, day, 9000, 2000)
        assertEquals(1, again.progress.violationDays.size)
        assertEquals(RuleEvent.NONE, again.event)
    }
    @Test fun repeatedViolationBlocksForAgreedStages() {
        val second = RuleEngine.evaluate(rule, expired(listOf(day.minusDays(1).toString())), day, 660, 1000)
        assertTrue(second.blocked)
        assertEquals(601000L, second.progress.blockedUntil)
        val third = RuleEngine.evaluate(rule, expired(listOf(day.minusDays(1).toString(), day.minusDays(2).toString())), day, 660, 1000)
        assertEquals(1801000L, third.progress.blockedUntil)
        assertFalse(RuleEngine.evaluate(rule, second.progress, day, 700, 601001).blocked)
    }
    @Test fun expiredViolationsDoNotEscalateAndNewDayResets() {
        val result = RuleEngine.evaluate(rule, expired(listOf(day.minusDays(7).toString())), day, 660, 1000)
        assertFalse(result.blocked)
        val tomorrow = RuleEngine.evaluate(rule, result.progress, day.plusDays(1), 0, 1000)
        assertFalse(tomorrow.progress.violationRecorded)
    }
    @Test fun reconnectCannotSkipWarningAndGrace() {
        val warning = RuleEngine.evaluate(rule, RuleProgress(), day, 1000, 0)
        assertEquals(RuleEvent.WARNING, warning.event)
        val grace = RuleEngine.evaluate(rule, warning.progress, day, 1000, 1000)
        assertEquals(RuleEvent.GRACE, grace.event)
        assertFalse(grace.blocked)
        val stillInGrace = RuleEngine.evaluate(rule, grace.progress, day, 1030, 2000)
        assertEquals(RuleEvent.NONE, stillInGrace.event)
        assertFalse(stillInGrace.progress.violationRecorded)
    }
    @Test fun foregroundClockExcludesLocksAppSwitchesAndStalls() {
        val clock = UsageClock()
        assertNull(clock.tick("a", true, 1000))
        assertEquals("a" to 1000L, clock.tick("a", true, 2000))
        assertNull(clock.tick("b", true, 3000))
        assertNull(clock.tick("b", false, 4000))
        assertNull(clock.tick("b", true, 5000))
        assertNull(clock.tick("b", true, 50000))
    }
    @Test fun inaccessibleAndAmbiguousContentRemainUnknown() {
        assertEquals(Purpose.UNKNOWN, ContextAnalyzer.analyze("").purpose)
        assertEquals(Purpose.UNKNOWN, ContextAnalyzer.analyze("유튜브").purpose)
        assertEquals(Purpose.UNKNOWN, ContextAnalyzer.analyze("숙제 게임").purpose)
        assertEquals(Purpose.STUDY, ContextAnalyzer.analyze("수학 강의 방정식 문제풀이").purpose)
    }
    @Test fun sharingProjectionExcludesPrivateTopicsEvidenceAppsAndNotes() {
        val rows = listOf(Observation(at = 0, app = "private.app", source = "OCR", analysis = Analysis(Purpose.STUDY, setOf("수학", "게임"), listOf("private words"), "추정"), note = "private note"))
        val private = ReportProjector.project(rows, Sharing())
        assertTrue(private.purposeCounts.isEmpty())
        assertTrue(private.interestCounts.isEmpty())
        assertTrue(private.notes.isEmpty())
        val partial = ReportProjector.project(rows, Sharing(interests = setOf("수학")))
        assertEquals(mapOf("수학" to 1), partial.interestCounts)
        assertFalse(partial.toString().contains("private"))
    }
    @Test fun repeatedFramesAreDeduplicatedButNewScreensRemain() {
        val gate = SampleGate()
        assertTrue(gate.accept("a", "hash", 1000))
        assertFalse(gate.accept("a", "hash", 2000))
        assertTrue(gate.accept("a", "new", 3000))
        assertTrue(gate.accept("b", "new", 3000))
    }
    @Test fun anActiveBreakSurvivesMidnight() {
        val previous = RuleProgress(day.toString(), violationRecorded = true, blockedUntil = 10000)
        val result = RuleEngine.evaluate(rule, previous, day.plusDays(1), 0, 5000)
        assertTrue(result.blocked)
        assertFalse(result.progress.violationRecorded)
    }
}
