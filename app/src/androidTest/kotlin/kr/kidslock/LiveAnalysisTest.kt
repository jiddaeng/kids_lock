package kr.kidslock

import android.app.UiAutomation
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.kidslock.data.*
import kr.kidslock.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveAnalysisTest {
    @Test fun deployedServerReturnsReasonFromRemoteQwen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore.get(context)
        val token = RelayCredential(context).read()
        assumeTrue("Provision the private QA relay token before running the live test", token.isNotBlank())
        val result = CloudClient.analyzeDetailed(store.endpoint, token, ScreenPrivacy.redact("수학 강의에서 일차방정식 문제 풀이를 공부하고 있어요."))
        assertEquals(Purpose.STUDY, result.analysis.purpose)
        assertTrue(result.analysis.interests.contains("수학"))
        assertEquals("교과 개념이나 문제 풀이가 화면에 나타났어요.", result.reason)
    }

    @Test fun realOcrToLocalQwenPersistsSummary() {
        assertEquals("Only dedicated emulator", "ranchu", Build.HARDWARE)
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore.get(context)
        val tokenFile = File(context.filesDir, "qa-relay-token")
        assertTrue("Provision private QA token before running", tokenFile.exists())
        val token = tokenFile.readText().trim()
        RelayCredential(context).save(token)
        store.endpoint = "https://gauroo-analysis.onrender.com"
        // Real Android TLS + actual deployed server. No mock response or seeded observations.
        CloudClient.checkConnection(store.endpoint, token)
        try {
            CloudClient.checkConnection(store.endpoint, "wrong-credential-".repeat(3))
            fail("Invalid credential must be rejected")
        } catch (e: CloudClient.Failure) { assertEquals("unauthorized", e.reason) }
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure enabled_accessibility_services kr.kidslock/kr.kidslock.capture.ScreenObserverService")
        shell("settings put secure accessibility_enabled 1")
        val fixture = "kr.kidslock.fixture"
        store.clearObservations()
        store.selectedApps = setOf(fixture)
        store.consent = true; store.cloudConsent = true; store.paused = false
        store.status = "실제 분석 대기"
        val since = System.currentTimeMillis()
        context.startActivity(Intent().setComponent(ComponentName(fixture, "$fixture.FixtureActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            val deadline = since + 210_000
            var switched = false
            while (System.currentTimeMillis() < deadline && store.observations(since).isEmpty()) {
                // Regression: leaving the observed app during an in-flight request must
                // preserve a consented observation, not silently discard its result.
                if (!switched && store.status == "화면 맥락을 분석하고 있어요") {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    switched = true
                }
                if (store.status.contains("한도") || store.status.contains("유효하지") || store.status.contains("통신에 실패")) break
                Thread.sleep(250)
            }
            val rows = store.observations(since)
            assertTrue("LIVE PIPELINE FAILED: ${store.status}", rows.isNotEmpty())
            assertTrue("Must switch apps during in-flight analysis", switched)
            assertEquals(fixture, rows.first().app)
            assertEquals("화면 OCR", rows.first().source)
            assertEquals(Purpose.STUDY, rows.first().purpose)
            assertTrue("Model attribution must match actual local model", rows.first().analysis.certainty.startsWith("로컬 Qwen3"))
            assertTrue("No OCR evidence persisted", rows.first().analysis.evidence.isEmpty())
            File(context.getExternalFilesDir("qa"), "live-result.txt").apply { parentFile!!.mkdirs(); writeText("REAL LOCAL QWEN3 + ML Kit OCR: PASS\nobservations=${rows.size}\npurpose=${rows.first().purpose}\napp-switch-preserved=true\n") }
        } finally {
            store.paused = true
            tokenFile.delete()
        }
    }
}
