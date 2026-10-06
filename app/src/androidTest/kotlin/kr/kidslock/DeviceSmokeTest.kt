package kr.kidslock

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.kidslock.core.*
import kr.kidslock.data.LocalStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun externalAppScreenshotOcrHonorsSecureWindowsAndConsentRevocation() {
        assertEquals("Automatic permission setup is only permitted on the test emulator", "ranchu", Build.HARDWARE)
        // am instrument force-stops the target process, which marks its previously bound
        // accessibility service as crashed. Rebind after the instrumentation process starts.
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure enabled_accessibility_services kr.kidslock/kr.kidslock.capture.ScreenObserverService")
        shell("settings put secure accessibility_enabled 1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore.get(context)
        val fixture = "kr.kidslock.fixture"
        val originalApps = store.selectedApps
        val originalMode = store.captureMode
        fun launch(secure: Boolean) {
            context.startActivity(Intent().setComponent(ComponentName(fixture, "$fixture.FixtureActivity"))
                .putExtra("secure", secure).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        val start = System.currentTimeMillis()
        try {
            store.cloudConsent = false; store.selectedApps = setOf(fixture); store.captureMode = "ocr"; store.consent = true; store.paused = false
            store.status = "테스트 OCR 대기"
            launch(false)
            val deadline = System.currentTimeMillis() + 45_000
            var observed = emptyList<Observation>()
            while (System.currentTimeMillis() < deadline) {
                observed = store.observations(start).filter { it.app == fixture && it.source == "화면 OCR" }
                if (store.status.startsWith("OCR 확인 완료")) break
                Thread.sleep(500)
            }
            assertTrue("External fixture OCR failed: ${store.status}", store.status.startsWith("OCR 확인 완료"))
            assertTrue("Without cloud consent no purpose result may be invented", observed.isEmpty())

            launch(true)
            Thread.sleep(2000)
            val protectedStart = System.currentTimeMillis()
            Thread.sleep(12_000)
            assertTrue("FLAG_SECURE content must not be recorded", store.observations(protectedStart).filter { it.app == fixture }.isEmpty())

            store.consent = false
            launch(false)
            val revokedStart = System.currentTimeMillis()
            Thread.sleep(12_000)
            assertTrue("Revoked capture must not store results", store.observations(revokedStart).filter { it.app == fixture }.isEmpty())
        } finally {
            store.consent = false; store.paused = true; store.selectedApps = originalApps; store.captureMode = originalMode
            store.writableDatabase.delete("observations", "app=?", arrayOf(fixture))
            store.writableDatabase.delete("usage", "app=?", arrayOf(fixture))
        }
    }
    @Test fun bundledKoreanOcrWorksOnRealAndroidRuntime() {
        val bitmap = Bitmap.createBitmap(1400, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        canvas.drawText("수학 강의 방정식 문제풀이", 50f, 150f, paint)
        canvas.drawText("오늘의 학습 시간", 50f, 280f, paint)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 40, TimeUnit.SECONDS)
            assertTrue("Bundled Korean model must recognize Korean text", result.text.contains("수학"))
            assertEquals(Purpose.STUDY, ContextAnalyzer.analyze(result.text).purpose)
        } finally { recognizer.close(); bitmap.recycle() }
    }

    @Test fun ocrInspectionDisplaysModelContextTopicsAndReason() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.javaClass.getDeclaredMethod("showOcrResult", String::class.java, String::class.java).apply { isAccessible = true }
                    .invoke(activity, "수학 강의 방정식 문제풀이", "AI가 본 맥락: 학습\n관심 주제: 수학\n판단 이유: 교과 개념이나 문제 풀이가 화면에 나타났어요.\n확신도: 로컬 Qwen3 · 높은 확신의 추정")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val dialogRoot = WindowInspector.getGlobalWindowViews().last { it.isShown && it !== activity.window.decorView }
                val visible = flatten(dialogRoot).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                assertTrue(visible.contains("AI가 본 맥락: 학습"))
                assertTrue(visible.contains("관심 주제: 수학"))
                assertTrue(visible.contains("판단 이유: 교과 개념이나 문제 풀이가 화면에 나타났어요."))
                assertTrue(visible.contains("OCR 원문과 이 확인 결과는 기록에 저장하지 않아요."))
            }
        }
    }

    @Test fun appStartsAndAllFiveTabsRender() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val names = listOf("오늘", "기록", "약속", "리포트", "설정")
            names.forEachIndexed { index, name ->
                scenario.onActivity { activity ->
                    val tab = flatten(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == name && it.isClickable }
                    assertTrue(tab.performClick())
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    assertTrue(view.width > 0 && view.height > 0)
                    // Render this app's own view for visual QA; no other app's protected content is captured.
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val captureDirectory = File(activity.filesDir, "qa").apply { mkdirs() }
                    File(captureDirectory, "0${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun tappingAWeeklyBarShowsThatDaysObservedUsage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore.get(context)
        val day = java.time.LocalDate.now().minusDays(2)
        val app = "weekly.chart.fixture"
        store.writableDatabase.execSQL("INSERT OR REPLACE INTO usage(day,app,millis) VALUES(?,?,?)", arrayOf<Any>(day.toString(), app, 17L * 60_000))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val column = flatten(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("$day 사용 17분") == true }
                    assertTrue(column.performClick())
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(350)
                scenario.onActivity { activity ->
                    val visible = flatten(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(visible.contains("${day.monthValue}월 ${day.dayOfMonth}일 관측한 사용"))
                    assertTrue(visible.contains("17분"))
                }
            }
        } finally { store.writableDatabase.delete("usage", "day=? AND app=?", arrayOf(day.toString(), app)) }
    }

    @Test fun everyTabStartsAtTopAndKeepsAllFiveNavigationItems() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(5) { index ->
                scenario.onActivity { activity ->
                    activity.javaClass.getDeclaredField("tab").apply { isAccessible = true }.setInt(activity, index)
                    activity.javaClass.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(500)
                scenario.onActivity { activity ->
                    val views = flatten(activity.window.decorView)
                    val scroll = views.filterIsInstance<android.widget.ScrollView>().single()
                    assertEquals("tab $index unexpectedly scrolled", 0, scroll.scrollY)
                    val navItems = views.filterIsInstance<TextView>().filter { it.contentDescription?.toString()?.endsWith("탭") == true }
                    assertEquals("tab $index navigation item count", 5, navItems.size)
                    assertTrue("tab $index has an unmeasured navigation item", navItems.all { it.width > 0 && it.isShown })
                }
            }
        }
    }

    @Test fun storagePersistsOnlySummaryAndProjectionRespectsSharing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStore.get(context)
        val at = System.currentTimeMillis() + 1000
        val secret = "개인 원문 문장 010-1234-5678 수학 강의"
        store.insert(Observation(at = at, app = "test.fixture", source = "테스트", analysis = ContextAnalyzer.analyze(secret)))
        try {
            val row = store.observations(at, at + 1).single()
            assertFalse(row.toString().contains("010-1234-5678"))
            assertEquals(Purpose.STUDY, row.purpose)
            val shared = ReportProjector.project(listOf(row), Sharing())
            assertTrue(shared.purposeCounts.isEmpty())
            assertTrue(shared.interestCounts.isEmpty())
        } finally { store.writableDatabase.delete("observations", "app=?", arrayOf("test.fixture")) }
    }

    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
