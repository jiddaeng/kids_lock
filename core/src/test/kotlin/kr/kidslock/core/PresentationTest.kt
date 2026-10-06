package kr.kidslock.core

import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    @Test fun unchangedScreensNeverResendAndFailedRequestsCanRetry() {
        val gate = ChangedScreenGate()
        assertTrue(gate.changed("app", "one"))
        assertTrue(gate.changed("app", "one")) // no commit after failure
        gate.commit("app", "one")
        assertFalse(gate.changed("app", "one"))
        assertTrue(gate.changed("app", "two"))
        assertTrue(gate.changed("other", "one"))
        gate.clear()
        assertTrue(gate.changed("app", "one"))
    }
    @Test fun identifiersAreRemovedBeforeCloudProcessing() {
        val result = ScreenPrivacy.redact("이름: 홍길동\n수학 강의 010-1234-5678 me@test.com https://site.test/private @nickname 900101-1234567")
        for (secret in listOf("홍길동", "010-1234-5678", "me@test.com", "site.test", "nickname", "900101")) assertFalse(result.contains(secret))
        assertTrue(result.contains("수학 강의"))
        assertEquals(ScreenPrivacy.fingerprint("a b"), ScreenPrivacy.fingerprint(ScreenPrivacy.redact("a  b")))
    }
    @Test fun generatedPersonalTextAndInvalidCategoriesCannotEnterSummary() {
        for (topic in listOf("홍길동", "원문 메시지", "010-1234-5678")) {
            assertTrue(runCatching { AnalysisVocabulary.result("STUDY", listOf(topic), "NONE", "high") }.isFailure)
        }
        val result = AnalysisVocabulary.result("GAME", listOf("Minecraft"), "POSSIBLE", "low")
        assertEquals(Purpose.UNKNOWN, result.purpose)
        assertEquals(Risk.UNKNOWN, result.risk)
        assertTrue(result.evidence.isEmpty())
    }
    @Test fun focusWindowsHandleMidnightAndRequireBothApprovals() {
        val rule = Rule("game", "게임", parentApproved = true, childApproved = true, studyStartMinute = 1320, studyEndMinute = 60)
        assertTrue(ContextRules.inStudyWindow(rule, 1380))
        assertTrue(ContextRules.inStudyWindow(rule, 0))
        assertFalse(ContextRules.inStudyWindow(rule, 60))
        assertFalse(ContextRules.inStudyWindow(rule.copy(childApproved = false), 1380))
        assertFalse(ContextRules.inStudyWindow(rule.copy(studyEndMinute = 1320), 1380))
    }
    @Test fun risk_is_not_an_immediate_or_unconsented_restriction() {
        val rule = Rule("app", "앱", parentApproved = true, childApproved = true, protectRisk = true)
        val possible = Analysis(Purpose.GAME, emptySet(), emptyList(), "추정", Risk.POSSIBLE)
        assertFalse(ContextRules.riskApplies(rule.copy(childApproved = false), possible))
        assertFalse(ContextRules.riskApplies(rule.copy(protectRisk = false), possible))
        assertFalse(ContextRules.riskApplies(rule, null))
        assertFalse(ContextRules.riskApplies(rule, possible.copy(risk = Risk.UNKNOWN)))
        assertTrue(ContextRules.riskApplies(rule, possible))
        val decision = RuleEngine.evaluate(rule.copy(limitSeconds = 1), RuleProgress(), java.time.LocalDate.of(2026, 9, 5), 1, 1000)
        assertEquals(RuleEvent.WARNING, decision.event)
        assertFalse(decision.blocked)
    }
    @Test fun parent_conversation_uses_only_shared_topics() {
        val row = Observation(at = 0, app = "private", source = "OCR", analysis = Analysis(Purpose.GAME, setOf("Minecraft", "언어"), emptyList(), "추정"))
        val projection = ReportProjector.project(listOf(row), Sharing(interests = setOf("언어")))
        val questions = ConversationCoach.questions(projection.interestCounts.keys).joinToString()
        assertFalse(questions.contains("Minecraft"))
        assertTrue(questions.contains("언어"))
        assertTrue(ConversationCoach.questions(emptySet()).isEmpty())
    }
}
