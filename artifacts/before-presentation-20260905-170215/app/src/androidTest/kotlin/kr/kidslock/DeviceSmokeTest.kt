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
            store.selectedApps = setOf(fixture); store.captureMode = "ocr"; store.consent = true; store.paused = false
            launch(false)
            val deadline = System.currentTimeMillis() + 45_000
            var observed = emptyList<Observation>()
            while (System.currentTimeMillis() < deadline) {
                observed = store.observations(start).filter { it.app == fixture && it.source == "화면 OCR" }
                if (observed.isNotEmpty()) break
                Thread.sleep(500)
            }
            assertTrue("External fixture capture failed: ${store.status}", observed.isNotEmpty())
            assertEquals(Purpose.STUDY, observed.first().purpose)

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
                    val directory = activity.getExternalFilesDir("qa")!!
                    directory.mkdirs()
                    File(directory, "0${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
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
