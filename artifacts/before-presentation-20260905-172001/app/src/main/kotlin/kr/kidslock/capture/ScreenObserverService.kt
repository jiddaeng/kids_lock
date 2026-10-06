package kr.kidslock.capture

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.kidslock.MainActivity
import kr.kidslock.R
import kr.kidslock.core.*
import kr.kidslock.data.LocalStore
import kr.kidslock.data.CloudClient
import kr.kidslock.data.RelayCredential
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Executors

class ScreenObserverService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val store by lazy { LocalStore.get(this) }
    private val usageClock = UsageClock()
    private val gate = ChangedScreenGate()
    private var cloudBusy = false
    private var retryAfter = 0L
    private var lastAnalysis: Pair<String, Analysis>? = null
    private var lastAnalysisAt = 0L
    private var nextCapture = 0L
    private var busy = false
    private var destroyed = false
    private var generation = 0
    private var curtain: LinearLayout? = null
    private var curtainLabel: TextView? = null
    private var blockedApp: String? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("consent", "paused", "apps", "mode", "cloudConsentV2", "endpoint")) {
            generation++
            gate.clear()
            lastAnalysis = null
            nextCapture = 0
            if (!enabled()) {
                removeCurtain()
                store.status = "분석이 멈춰 있어요"
                updateNotification("분석 일시정지 · 화면을 읽지 않고 있어요")
            }
        }
    }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            tick()
            main.postDelayed(this, 1000)
        }
    }
    private val capture = Runnable { sample() }

    override fun onCreate() {
        super.onCreate()
        store.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        destroyed = false
        store.status = "연결됨 · 선택한 앱에서 분석 대기"
        createChannel()
        updateNotification(if (enabled()) "선택한 앱만 분석합니다 · 설정에서 일시정지 가능" else "분석 대기 · 앱에서 수집 동의와 시작이 필요해요")
        main.removeCallbacks(ticker)
        main.post(ticker)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled()) return
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { generation++; lastAnalysis = null }
        if (pkg !in store.selectedApps || pkg == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            main.removeCallbacks(capture)
            main.postDelayed(capture, 1200)
        }
    }
    override fun onInterrupt() {
        generation++
        removeCurtain()
        store.status = "서비스가 중단되었습니다 · 접근성 설정을 확인해 주세요"
    }
    override fun onDestroy() {
        destroyed = true
        generation++
        store.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        main.removeCallbacksAndMessages(null)
        removeCurtain()
        recognizer.close()
        worker.shutdown()
        getSystemService(NotificationManager::class.java).cancel(101)
        store.status = "접근성 서비스 연결이 해제되었습니다"
        super.onDestroy()
    }
    private fun enabled() = store.consent && !store.paused
    private fun interactive(): Boolean = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun appWindow(): AccessibilityWindowInfo? = runCatching {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }.firstOrNull()
    }.getOrNull()

    private fun foregroundPackage(): String? {
        val root = appWindow()?.root ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }

    private fun tick() {
        val awake = interactive()
        val pkg = if (awake) foregroundPackage() else null
        val selected = enabled() && pkg != packageName && pkg in store.selectedApps
        val increment = usageClock.tick(if (selected) pkg else null, awake, SystemClock.elapsedRealtime())
        increment?.let { store.addUsage(it.first, it.second) }
        if (!selected || pkg == null) {
            removeCurtain()
            return
        }
        for (rule in store.rules().filter { (it.packageName == pkg || it.packageName == "__all__") && it.active }) {
            val previous = store.progress(rule.packageName)
            val usage = store.todayUsage()
            val used = if (rule.packageName == "__all__") usage.values.sum() else usage[pkg] ?: 0
            val decision = RuleEngine.evaluate(rule, previous, LocalDate.now(), used, System.currentTimeMillis())
            if (previous != decision.progress) store.saveProgress(rule.packageName, decision.progress)
            if (decision.event != RuleEvent.NONE) announce(rule, decision)
            if (decision.blocked) {
                showCurtain(pkg, "${rule.label} · 함께 정한 휴식 시간", decision.progress.blockedUntil)
                return
            }
            val time = java.time.LocalTime.now()
            val recent = lastAnalysis?.takeIf { it.first == pkg && SystemClock.elapsedRealtime() - lastAnalysisAt < 20_000 }?.second
            val conditions = listOf("focus" to ContextRules.inStudyWindow(rule, time.hour * 60 + time.minute), "risk" to ContextRules.riskApplies(rule, recent))
            for ((kind, applies) in conditions) {
                val key = "$kind:${rule.packageName}"
                val ongoing = store.progress(key)
                if (kind == "risk" && ongoing.blockedUntil > System.currentTimeMillis()) {
                    showCurtain(pkg, rule.label + " · 함께 정한 휴식", ongoing.blockedUntil); return
                }
                if (!applies) continue
                val elapsed = store.contextSeconds(key, increment?.second ?: 0)
                val contextRule = rule.copy(label = rule.label + if (kind == "focus") " · 학습 보호" else " · 유해 가능성", limitSeconds = 1)
                val prior = store.progress(key)
                val contextDecision = RuleEngine.evaluate(contextRule, prior, LocalDate.now(), elapsed, System.currentTimeMillis())
                if (prior != contextDecision.progress) store.saveProgress(key, contextDecision.progress)
                if (contextDecision.event != RuleEvent.NONE) announce(contextRule, contextDecision)
                if (contextDecision.blocked) { showCurtain(pkg, contextRule.label, contextDecision.progress.blockedUntil); return }
            }
        }
        removeCurtain()
        if (SystemClock.elapsedRealtime() >= nextCapture) sample()
    }

    private data class ScreenText(val text: String, val sensitive: Boolean)
    private fun treeText(root: AccessibilityNodeInfo): ScreenText {
        val parts = linkedSetOf<String>()
        var sensitive = false
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (++visited > 160 || sensitive) return
            if (node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) {
                sensitive = true
                return
            }
            if (node.isVisibleToUser) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it.take(500) }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it.take(500) }
            }
            for (i in 0 until node.childCount.coerceAtMost(50)) {
                val child = node.getChild(i) ?: continue
                try { visit(child) } finally { child.recycle() }
            }
        }
        visit(root)
        return ScreenText(parts.joinToString("\n").take(6000), sensitive)
    }

    private fun sample() {
        if (!enabled() || !interactive() || busy || cloudBusy || destroyed || curtain != null) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextCapture) return
        nextCapture = now + 10_000
        val window = appWindow() ?: return
        val root = window.root ?: return
        val pkg = root.packageName?.toString()
        if (pkg == null || pkg == packageName || pkg !in store.selectedApps) { root.recycle(); return }
        val text = try { treeText(root) } finally { root.recycle() }
        if (text.sensitive) { store.status = "보호된 화면은 분석하지 않았어요"; return }
        busy = true
        val token = generation
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                } catch (_: RuntimeException) { null } finally { buffer.close() }
                main.post {
                    if (bitmap == null) { busy = false; return@post }
                    if (token != generation || !canRecord(pkg)) { bitmap.recycle(); busy = false; return@post }
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { recognized ->
                            if (token == generation && canRecord(pkg)) record(pkg, recognized.text, "화면 OCR")
                        }
                        .addOnFailureListener { store.status = "OCR 처리 실패 · 다음 화면에서 다시 시도" }
                        .addOnCompleteListener { bitmap.recycle(); busy = false }
                }
            }
            override fun onFailure(errorCode: Int) {
                main.post {
                    busy = false
                    store.status = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "캡처가 보호된 화면은 건너뛰었어요"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "화면 분석 간격을 조정하고 있어요"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "접근성 권한을 확인해 주세요"
                        else -> "화면 캡처 불가 ($errorCode) · 데이터 없음으로 처리"
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(window.id, worker, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, worker, callback)
        } catch (_: SecurityException) {
            busy = false
            store.status = "화면 캡처 권한을 사용할 수 없어요"
        } catch (_: IllegalStateException) { busy = false }
    }

    private fun canRecord(pkg: String): Boolean = !destroyed && enabled() && interactive() && pkg in store.selectedApps && foregroundPackage() == pkg
    private fun record(pkg: String, text: String, source: String) {
        if (!canRecord(pkg)) return
        if (text.isBlank()) { store.status = "읽을 수 있는 글자가 없어 분석을 보류했어요"; return }
        if (!store.cloudConsent) { store.status = "OCR 확인 완료 · 외부 맥락 분석 동의가 필요해요"; return }
        if (!CloudClient.validEndpoint(store.endpoint)) { store.status = "OCR 확인 완료 · 분석 서버를 설정해 주세요"; return }
        if (cloudBusy || SystemClock.elapsedRealtime() < retryAfter) return
        val redacted = ScreenPrivacy.redact(text)
        if (redacted.isBlank()) return
        val fingerprint = ScreenPrivacy.fingerprint(redacted)
        if (!gate.changed(pkg, fingerprint)) { lastAnalysisAt = SystemClock.elapsedRealtime(); store.status = "화면 변화 없음 · 추가 AI 호출 생략"; return }
        lastAnalysis = null
        val token = generation
        val endpoint = store.endpoint
        val credential = RelayCredential(this).read()
        if (credential.isBlank()) { store.status = "분석 서버 연결 토큰을 설정해 주세요"; return }
        val observedAt = System.currentTimeMillis()
        cloudBusy = true
        store.status = "화면 맥락을 분석하고 있어요"
        worker.execute {
            val result = runCatching { CloudClient.analyze(endpoint, credential, redacted) }
            main.post {
                cloudBusy = false
                if (token != generation || !canRecord(pkg) || !store.cloudConsent || endpoint != store.endpoint) return@post
                result.onSuccess { analysis ->
                    gate.commit(pkg, fingerprint)
                    lastAnalysis = pkg to analysis
                    lastAnalysisAt = SystemClock.elapsedRealtime()
                    store.insert(Observation(at = observedAt, app = pkg, source = source, analysis = analysis))
                    store.status = "맥락 분석 완료 · 원문은 저장하지 않았어요"
                    if (analysis.risk == Risk.POSSIBLE) updateNotification(ConversationCoach.message(analysis))
                }.onFailure {
                    retryAfter = SystemClock.elapsedRealtime() + 60_000
                    lastAnalysis = null
                    store.status = "AI 분석을 완료하지 못했어요 · 서버 연결을 확인해 주세요"
                }
            }
        }
    }

    private fun announce(rule: Rule, decision: RuleDecision) {
        val message = when (decision.event) {
            RuleEvent.WARNING -> "${rule.label} · 약속한 사용시간이 얼마 남지 않았어요"
            RuleEvent.GRACE -> "${rule.label} · ${rule.graceSeconds / 60}분 동안 마무리해 주세요"
            RuleEvent.FIRST_VIOLATION -> "첫 초과를 기록했어요. 이번에는 제한하지 않아요"
            RuleEvent.BLOCK -> "반복 초과로 함께 정한 휴식 시간이 시작돼요"
            else -> return
        }
        val coaching = lastAnalysis?.takeIf { it.first == rule.packageName }?.second?.let { "\n" + ConversationCoach.message(it) } ?: ""
        updateNotification(message + coaching)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("monitor", "화면 분석 및 약속 알림", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun updateNotification(message: String) {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "monitor").setSmallIcon(R.drawable.ic_kidslock).setContentTitle("가루").setContentText(message).setContentIntent(intent).setOngoing(true).build()
        try { getSystemService(NotificationManager::class.java).notify(101, notification) } catch (_: SecurityException) { /* Explicit notification permission may be declined. */ }
    }
    private fun showCurtain(pkg: String, title: String, until: Long) {
        if (blockedApp != pkg) removeCurtain()
        val remaining = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        if (curtain == null) {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48); setBackgroundColor(Color.rgb(241, 247, 240))
            }
            val heading = TextView(this).apply { text = "잠깐, 함께 정한 휴식이에요"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.rgb(30, 62, 47)) }
            curtainLabel = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 36, 0, 36) }
            val home = Button(this).apply { text = "홈으로 이동"; setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME); removeCurtain() } }
            val explain = Button(this).apply {
                text = "가루에서 약속 확인"
                setOnClickListener { startActivity(Intent(this@ScreenObserverService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); removeCurtain() }
            }
            layout.addView(heading); layout.addView(curtainLabel); layout.addView(home); layout.addView(explain)
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            try { wm.addView(layout, params); curtain = layout; blockedApp = pkg } catch (_: RuntimeException) { store.status = "제한 화면 표시 실패 · 약속 탭을 확인해 주세요" }
        }
        curtainLabel?.text = "$title\n${remaining / 60}분 ${remaining % 60}초 뒤 다시 사용할 수 있어요\n\n통화와 다른 앱은 계속 사용할 수 있어요."
    }
    private fun removeCurtain() {
        curtain?.let { runCatching { wm.removeView(it) } }
        curtain = null; curtainLabel = null; blockedApp = null
    }
}
