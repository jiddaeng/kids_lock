package kr.kidslock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.kidslock.core.*
import kr.kidslock.data.LocalStore
import kr.kidslock.data.CloudClient
import kr.kidslock.data.RelayCredential
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val store by lazy { LocalStore.get(this) }
    private val ink = Color.rgb(35, 31, 27)
    private val muted = Color.rgb(139, 126, 106)
    private val accent = Color.rgb(247, 197, 99)
    private val accentDark = Color.rgb(176, 112, 9)
    private val pale = Color.rgb(255, 246, 230)
    private val canvasColor = Color.rgb(250, 250, 250)
    private val green = Color.rgb(151, 184, 126)
    private val sky = Color.rgb(151, 200, 214)
    private val warmLine = Color.rgb(235, 216, 181)
    private lateinit var content: LinearLayout
    private var tab = 0
    private var periodDays = 7
    private var selectedUsageDay: LocalDate? = null
    private var privateDialog: AlertDialog? = null
    private val statusListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "status" && tab in 0..1 && privateDialog == null) window.decorView.post {
            if (!isFinishing && !isDestroyed && hasWindowFocus()) {
                val position = (content.parent as? ScrollView)?.scrollY ?: 0
                render(); (content.parent as? ScrollView)?.post { (content.parent as? ScrollView)?.scrollTo(0, position) }
            }
        }
    }
    private val tabs = listOf("오늘", "기록", "약속", "리포트", "설정")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        periodDays = savedInstanceState?.getInt("periodDays") ?: 7
        selectedUsageDay = savedInstanceState?.getString("selectedUsageDay")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", tab); outState.putInt("periodDays", periodDays); selectedUsageDay?.let { outState.putString("selectedUsageDay", it.toString()) }; super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); store.prefs.registerOnSharedPreferenceChangeListener(statusListener); if (::content.isInitialized) render() }
    override fun onPause() { store.prefs.unregisterOnSharedPreferenceChangeListener(statusListener); privateDialog?.dismiss(); privateDialog = null; super.onPause() }

    private fun render() {
        window.statusBarColor = accent
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(canvasColor) }
        shell.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val frame = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), dp(112)) }
        scroll.addView(content)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = rounded(Color.WHITE, 32)
            elevation = dp(10).toFloat()
        }
        tabs.forEachIndexed { index, label ->
            nav.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; minHeight = dp(44)
                setTextColor(if (index == tab) Color.WHITE else ink)
                setTypeface(typeface, if (index == tab) Typeface.BOLD else Typeface.NORMAL)
                if (index == tab) background = rounded(accent, 25)
                contentDescription = "$label 탭"
                setOnClickListener { tab = index; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        frame.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(28); rightMargin = dp(28); bottomMargin = dp(16) })
        shell.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)
        when (tab) { 0 -> today(); 1 -> records(); 2 -> rules(); 3 -> report(); else -> settings() }
        shell.requestApplyInsets()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun today() {
        title("나의 사용시간", "나의 휴대폰 사용 시간을 확인해요", "⌛")
        val period = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(pale, 24)
        }
        listOf(1 to "일간", 7 to "주간", 30 to "월간").forEach { (days, label) ->
            period.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; minHeight = dp(38)
                setTextColor(if (periodDays == days) Color.WHITE else muted)
                if (periodDays == days) { background = rounded(accent, 22); setTypeface(typeface, Typeface.BOLD) }
                setOnClickListener { periodDays = days; selectedUsageDay = null; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(period, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        val since = java.time.LocalDate.now().minusDays((periodDays - 1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val observations = store.observations(since)
        val daily = store.usageByDay(periodDays)
        val validDays = daily.keys.map(LocalDate::parse)
        val selectedDay = selectedUsageDay?.takeIf { it in validDays } ?: LocalDate.now()
        selectedUsageDay = selectedDay
        card(pale) {
            val heading = if (periodDays == 1) "오늘 하루 사용 추이" else "최근 ${periodDays}일 사용 추이"
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(heading, 17, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(duration(daily.values.sum()), 16, accentDark, true))
            })
            val max = daily.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            val chart = LinearLayout(this@MainActivity).apply { gravity = Gravity.BOTTOM; setPadding(0, dp(16), 0, 0) }
            daily.entries.forEachIndexed { index, (dayText, seconds) ->
                val day = LocalDate.parse(dayText)
                val selected = day == selectedDay
                val showLabel = periodDays <= 7 || index % 5 == 0 || index == daily.size - 1
                val column = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    contentDescription = "$dayText 사용 ${seconds / 60}분${if (selected) ", 선택됨" else ""}"
                    isClickable = true; isFocusable = true
                    setOnClickListener { selectedUsageDay = day; renderPreservingScroll() }
                }
                val height = (seconds * 88 / max).toInt().coerceAtLeast(3)
                val bar = View(this@MainActivity).apply { background = rounded(if (selected) accentDark else accent, 8) }
                column.addView(bar, LinearLayout.LayoutParams(-1, dp(height)).apply { leftMargin = dp(if (periodDays == 30) 2 else 7); rightMargin = dp(if (periodDays == 30) 2 else 7) })
                column.addView(text(if (showLabel) dayText.substring(8) else "", 11, if (selected) ink else muted, selected).apply { gravity = Gravity.CENTER; minHeight = dp(28) })
                chart.addView(column, LinearLayout.LayoutParams(0, dp(126), 1f))
            }
            addView(chart)
            addView(text("막대를 누르면 그날의 앱별 사용을 볼 수 있어요.", 11, muted).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        val usage = store.usageForDay(selectedDay.toString())
        card(pale) {
            val dayLabel = if (selectedDay == LocalDate.now()) "오늘 관측한 사용" else "${selectedDay.monthValue}월 ${selectedDay.dayOfMonth}일 관측한 사용"
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(dayLabel, 17, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(duration(usage.values.sum()), 15, accentDark, true))
            })
            if (usage.isEmpty()) addView(text("이날 관측된 앱 사용이 없어요.", 14, muted).spaced())
            else usage.entries.take(8).forEach { addUsageRow(it.key, it.value) }
            addView(text("분석이 켜져 있고 선택한 앱이 화면에 있을 때만 집계해요.", 11, muted).spaced())
        }
        card {
            addView(text("최근 ${periodDays}일의 맥락", 17, ink, true))
            addView(text("${observations.size}건의 AI 분석 완료", 13, muted).spaced())
            if (observations.isEmpty()) addView(text(analysisReadiness(), 14, ink))
            else observations.groupingBy { it.purpose.label }.eachCount().forEach { (label, count) -> row(label, "${count}건 · ${count * 100 / observations.size}%") }
            addView(text("비율은 변화 화면의 분석 건수 기준이며 목적별 사용시간 비율이 아니에요.", 12, muted).spaced())
            action("분석 상태 새로고침", secondary = true) { render() }
            action("내 기록 확인", secondary = true) { tab = 1; render() }
        }
        val running = store.consent && !store.paused && accessibilityEnabled()
        card(pale) {
            addView(text(if (running) "●  화면 분석 켜짐" else "○  화면 분석 준비", 14, if (running) green else accentDark, true))
            addView(text(if (running) store.status else "선택한 앱의 화면을 기기 안에서 읽어요. 원본 이미지와 OCR 전문은 저장하지 않아요.", 13, muted).spaced())
            action(if (running) "분석 잠시 멈추기" else "분석 시작하기") {
                if (running) { store.paused = true; render() } else startAnalysis()
            }
        }
        foot("현재 버전은 한 기기에서 가족이 함께 사용하는 개발용 MVP예요. 부모의 다른 휴대폰과 연결되지는 않아요.")
    }

    private fun records() {
        title("내가 설명하는 기록", "분석이 다르면 고치고, 나의 설명을 덧붙여요", "📝")
        val rows = store.observations()
        if (rows.isEmpty()) empty("아직 분석 기록이 없어요", analysisReadiness())
        rows.take(80).forEach { observation ->
            card(pale) {
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(appBadge(observation.app), LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(12) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(observation.purpose.label, 18, ink, true))
                        addView(text("${appLabel(observation.app)} · ${time(observation.at)}", 12, muted).apply { setPadding(0, dp(3), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(chip(if (observation.correctedPurpose != null) "내가 수정함" else "자동 추정", green))
                })
                divider()
                addView(text("${observation.source} · ${observation.analysis.certainty}", 12, muted))
                addView(text("관심 주제: ${observation.analysis.interests.joinToString().ifEmpty { "근거 부족" }}", 14, ink).spaced())
                addView(text("위험 가능성: ${observation.analysis.risk.label}", 12, muted))
                addView(text(ConversationCoach.message(observation.analysis), 14, ink).spaced())
                if (observation.note.isNotBlank()) addView(text("내 설명: ${observation.note}", 14, ink).spaced())
                action("설명/분석 수정하기") { explain(observation) }
            }
        }
        foot("관측 건수는 실제 시청 횟수나 사용시간이 아니에요. AI 분류는 실제 의도를 확정하지 않으며, 복합 목적은 미확인으로 남을 수 있어요. 요약은 최근 62일까지만 보관해요.")
    }

    private fun rules() {
        title("함께 정한 약속", "부모님과 정한 약속을 지켜요", "📋")
        val usage = store.todayUsage()
        val rules = store.rules()
        card(pale) {
            addView(text("상호 동의 하에 정한 약속", 13, muted))
            if (rules.isEmpty()) {
                addView(text("아직 정한 약속이 없어요", 18, ink, true).spaced())
            } else {
                addView(text(rules.joinToString(" · ") { "${it.label} ${duration(it.limitSeconds)}" }, 18, ink, true).spaced())
                addView(categoryUsageRows(usage, store.observations()))
                addView(text("오늘 ${duration(usage.values.sum())} 사용", 12, muted).apply { setPadding(0, dp(10), 0, 0) })
            }
            action("보호자가 약속 제안") { parentGate { editRule(null, true) } }
        }
        card(Color.WHITE) {
            addView(text("약속이 지켜지는 방식", 17, ink, true))
            addView(text("경고 → 유예 → 반복 시 휴식", 15, accentDark, true).spaced())
            addView(text("최근 7일 동안 첫 초과는 경고, 두 번째는 10분, 세 번째부터는 30분 쉬어요. 유예는 해당 앱을 실제로 사용한 시간만 계산해요.", 13, muted))
        }
        if (rules.isEmpty()) empty("아직 약속이 없어요", "설정에서 분석할 앱을 고른 뒤 첫 약속을 만들어 주세요.")
        rules.forEach { rule ->
            card(pale) {
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(rule.label, 17, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(chip(if (rule.active) "함께 동의함" else "동의 대기", if (rule.active) green else accent))
                })
                addView(text("하루 ${rule.limitSeconds / 60}분 · 유예 ${rule.graceSeconds / 60}분", 17, ink, true).spaced())
                divider()
                addView(text("부모 ${if (rule.parentApproved) "동의" else "대기"}  /  자녀 ${if (rule.childApproved) "동의" else "대기"}", 13, muted))
                addView(text("오늘 관측 ${((if (rule.packageName == "__all__") usage.values.sum() else usage[rule.packageName] ?: 0) / 60)}분", 13, muted).spaced())
                if (rule.packageName != "__all__" && rule.packageName !in store.selectedApps) addView(text("현재 분석 대상에서 제외되어 적용되지 않아요.", 13, Color.rgb(147, 83, 44)))
                if (rule.studyStartMinute >= 0) addView(text("학습 보호 ${clockTime(rule.studyStartMinute)}–${clockTime(rule.studyEndMinute)} · 이 대상 앱에서 경고·유예·반복 시 휴식", 13, muted).spaced())
                if (rule.protectRisk) addView(text("유해 가능성 관측 시 경고·유예·반복 시 휴식", 13, muted).spaced())
                if (!rule.childApproved) action("자녀 · 이 약속에 동의") {
                    AlertDialog.Builder(this@MainActivity).setTitle("약속에 동의할까요?")
                        .setMessage("${rule.label}: 하루 ${rule.limitSeconds / 60}분, 유예 ${rule.graceSeconds / 60}분. 최근 7일의 반복 초과에 따라 10분·30분 휴식을 적용해요.")
                        .setPositiveButton("동의") { _, _ -> store.saveRule(rule.copy(childApproved = true)); render() }.setNegativeButton("아직", null).show()
                }
                if (!rule.parentApproved) action("부모 · 제안 승인", true) { parentGate { store.saveRule(rule.copy(parentApproved = true)); render() } }
                action("자녀 · 변경 제안", true) { editRule(rule, false) }
                action("부모 · 변경 제안", true) { parentGate { editRule(rule, true) } }
            }
        }
    }

    private fun report() {
        title("사용 내역 분석", "나의 관심사를 확인해요", "📊")
        val rows = store.observations()
        val topics = rows.flatMap { it.analysis.interests }.distinct().sorted()
        val ranked = rows.flatMap { it.analysis.interests }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        card(pale) {
            addView(sectionHeading("이번 주 관심사", "나만 보기", green))
            divider()
            if (ranked.isEmpty()) addView(text("분석이 쌓이면 관심사와 대화 소재를 보여드려요.", 14, muted).spaced())
            ranked.take(3).forEachIndexed { i, entry ->
                val supporting = rows.filter { entry.key in it.analysis.interests }
                val purpose = supporting.filter { it.purpose != Purpose.UNKNOWN }
                    .groupingBy { it.purpose.label }.eachCount().maxByOrNull { it.value }?.key ?: "관심 주제"
                val apps = supporting.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }
                addView(reportRankRow(i + 1, entry.key, "$purpose · 관련 관측 ${entry.value}건", apps.take(2).map { it.key }))
            }
            val patterns = rows.filter { it.purpose != Purpose.UNKNOWN }.groupingBy { observation ->
                val local = Instant.ofEpochMilli(observation.at).atZone(ZoneId.systemDefault())
                "${if (local.dayOfWeek.value >= 6) "주말" else "평일"} ${if (local.hour < 12) "오전" else if (local.hour < 18) "오후" else "저녁"} · ${observation.purpose.label}"
            }.eachCount().entries.sortedByDescending { it.value }
            if (patterns.isNotEmpty()) {
                divider()
                addView(text("자주 나타난 사용 흐름", 14, ink, true))
                patterns.take(3).forEach { row(it.key, "${it.value}건") }
            }
            addView(text("관심 순위와 패턴은 관측된 화면 기준이에요. 실제 관심도나 전체 이용시간을 단정하지 않아요.", 12, muted).spaced())
        }
        var choice = store.sharing
        card(pale) {
            addView(sectionHeading("부모님과 공유할 관심사", "내가 선택", sky))
            divider()
            check("사용 목적별 관측 건수 공유", choice.purposes) { checked -> choice = store.sharing.copy(purposes = checked); store.sharing = choice }
            check("내가 작성한 설명 공유", choice.notes) { checked -> choice = store.sharing.copy(notes = checked); store.sharing = choice }
            addView(text("공유할 관심 주제", 14, ink, true).spaced())
            if (topics.isEmpty()) addView(text("분석한 주제가 쌓이면 여기에서 고를 수 있어요.", 13, muted).spaced())
            topics.forEach { topic -> check(topic, topic in choice.interests) { checked ->
                val previous = store.sharing
                store.sharing = previous.copy(interests = if (checked) previous.interests + topic else previous.interests - topic)
            } }
            action("공유할 항목 선택하기") { showSharedReport(false) }
        }
        card(pale) {
            addView(sectionHeading("관심사 변화", "나만 보기", green))
            divider()
            val week = 7L * 86400000
            val cutoff = System.currentTimeMillis() - week
            val before = store.observations(cutoff - week, cutoff)
            if (before.isEmpty()) addView(text("이전 7일의 기록이 없어 변화 비교를 보류했어요.", 14, muted).spaced())
            else {
                val nowCounts = rows.flatMap { it.analysis.interests }.groupingBy { it }.eachCount()
                val oldCounts = before.flatMap { it.analysis.interests }.groupingBy { it }.eachCount()
                (nowCounts.keys + oldCounts.keys).sorted().forEach { topic -> row(topic, "${oldCounts[topic] ?: 0} → ${nowCounts[topic] ?: 0}건") }
                addView(text("두 기간의 관측량이 다를 수 있어요. 관심이 늘거나 줄었다는 확정 판단은 아니에요.", 12, muted).spaced())
            }
        }
        card(pale) {
            addView(text("부모와 함께 확인", 17, ink, true))
            addView(text("이 기기에서 보호자 PIN으로 열어요. 선택하지 않은 주제·앱 이름·원문·분류 단서는 공유 화면에 포함하지 않아요.", 13, muted).spaced())
            action("부모님과 공유한 리포트 보기", true) { parentGate { showSharedReport(true) } }
        }
        foot("공유를 끄면 이후 앱 안의 공개 화면에서 제외돼요. 상대가 이미 보거나 따로 적어 둔 정보까지 회수할 수는 없어요.")
    }

    private fun settings() {
        title("나의 설정", "가루가 나를 돕는 방식을 정해요", "⚙️")
        card(pale) {
            row("화면 분석 동의", if (store.consent) "동의함" else "필요")
            row("접근성 연결", if (accessibilityEnabled()) "연결됨" else "꺼짐")
            row("분석 대상", "${store.selectedApps.size}개 앱")
            action("분석할 앱 선택") { chooseApps() }
            action("화면 분석 동의 / 시작", true) { startAnalysis() }
            action("접근성 설정 열기", true) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            if (Build.VERSION.SDK_INT >= 33) action("경고 알림 허용", true) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) }
        }
        card(pale) {
            addView(text("분석 방식", 17, ink, true))
            addView(text("기기에서 화면을 약 10초마다 OCR로 읽고, 내용이 달라질 때만 AI로 분석해요. 원본 이미지는 전송하지 않아요.", 14, ink).spaced())
            row("AI 연결 설정", if (CloudClient.validEndpoint(store.endpoint) && RelayCredential(this@MainActivity).read().isNotBlank()) "주소·토큰 저장됨" else "토큰 설정 필요")
            addView(text("AI는 내 PC에서 실행해요. PC에서 가루 로컬 분석 시작을 실행하고 PC를 켜 두세요.", 13, muted).spaced())
            row("외부 분석 동의", if (store.cloudConsent) "동의함" else "필요")
            action("분석 서버 연결", true) { parentGate { configureRelay() } }
            action("AI 맥락 분석 동의", true) { consentCloud() }
            if (store.cloudConsent) action("외부 분석 동의 철회", true) { store.cloudConsent = false; render() }
            action("이미지로 한국어 OCR 확인", true) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 41)
            }
        }
        card(pale) {
            addView(text("보호자와 개인정보", 17, ink, true))
            action(if (store.hasParentPin) "보호자 PIN 변경" else "보호자 PIN 설정", true) {
                if (store.hasParentPin) parentGate { configurePin {} } else configurePin {}
            }
            action("분석 중지 및 동의 철회", true) {
                store.paused = true; store.consent = false; store.cloudConsent = false; store.sharing = Sharing(); render()
            }
            action("분석 기록 모두 삭제", true) {
                AlertDialog.Builder(this@MainActivity).setTitle("분석 기록을 삭제할까요?").setMessage("저장된 분류·주제·설명과 공유 선택을 삭제하고 분석을 멈춰요. 합의된 약속과 오늘 사용시간은 유지돼요.")
                    .setPositiveButton("삭제") { _, _ -> store.paused = true; store.clearObservations(); render() }.setNegativeButton("취소", null).show()
            }
        }
        foot("가루 0.3.2 · 개발용 MVP\nML Kit OCR · 동의한 텍스트의 로컬 Qwen3 분석 · 원본 캡처 저장 없음\n가족 월 20,000원 / 자녀 추가 5,000원 / 1개월 체험은 사업계획서 기준이며, 이 개발 버전에는 결제와 원격 가족 연결을 아직 연결하지 않았어요.")
    }

    private fun startAnalysis() {
        if (store.selectedApps.isEmpty()) { chooseApps(); return }
        if (!store.consent) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
            body.addView(text("선택한 앱의 화면 텍스트와 스크린샷을 읽어 사용 맥락과 관심 주제를 추정해요.\n\nOCR은 이 기기에서 이루어져요. 원본 이미지와 OCR 전문을 기기에 저장하지 않으며, 목적·주제·위험 가능성 요약을 최대 62일 보관해요. 외부 AI 분석은 별도 연결과 동의가 필요해요.\n\n합의한 앱에서는 사용시간을 세고 경고·유예·반복 초과 시 일시 제한을 적용해요. 동의 철회와 분석 중지는 언제든 가능해요.", 14, ink))
            val guardian = CheckBox(this).apply { text = "보호자가 설명을 확인하고 동의했어요" }
            val child = CheckBox(this).apply { text = "자녀가 수집 범위를 이해하고 동의했어요" }
            body.addView(guardian); body.addView(child)
            val dialog = AlertDialog.Builder(this).setTitle("화면 분석을 시작하기 전에").setView(body).setPositiveButton("동의하고 설정", null).setNegativeButton("취소", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!guardian.isChecked || !child.isChecked) { toast("부모와 자녀가 내용을 함께 확인해 주세요"); return@setOnClickListener }
                store.consent = true; store.paused = false; dialog.dismiss()
                if (!accessibilityEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else render()
            } }
            dialog.show(); return
        }
        store.paused = false
        if (!accessibilityEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else render()
    }

    private fun installedApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val protectedPackages = setOf(packageName, "com.android.settings", "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.android.contacts", "com.samsung.android.contacts")
        return packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.filterNot { it.first in protectedPackages }.sortedBy { it.second }
    }
    private fun chooseApps() {
        val apps = installedApps()
        if (apps.isEmpty()) { toast("선택할 수 있는 앱이 없어요"); return }
        val checked = apps.map { it.first in store.selectedApps }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("분석할 앱만 선택해 주세요")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("선택 저장") { _, _ -> store.selectedApps = apps.filterIndexed { index, _ -> checked[index] }.map { it.first }.toSet(); render() }
            .setNegativeButton("취소", null).show()
    }

    private fun editRule(existing: Rule?, byParent: Boolean) {
        val apps = listOf("__all__" to "선택한 앱 전체") + installedApps().filter { it.first in store.selectedApps }
        if (apps.isEmpty()) { toast("설정에서 분석할 앱을 먼저 선택해 주세요"); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, apps.map { it.second }); minimumHeight = dp(48) }
        if (existing != null) { spinner.setSelection(apps.indexOfFirst { it.first == existing.packageName }.coerceAtLeast(0)); spinner.isEnabled = false }
        val limit = field("하루 사용시간 (분)", InputType.TYPE_CLASS_NUMBER).apply { setText(((existing?.limitSeconds ?: 3600) / 60).toString()) }
        val grace = field("유예 시간 (분)", InputType.TYPE_CLASS_NUMBER).apply { setText(((existing?.graceSeconds ?: 300) / 60).toString()) }
        form.addView(text("대상 앱", 13, muted)); form.addView(spinner)
        form.addView(text("하루 사용시간 · 분", 13, muted).spaced()); form.addView(limit)
        form.addView(text("마무리 유예 · 분", 13, muted).spaced()); form.addView(grace)
        form.addView(text("첫 초과는 경고, 최근 7일의 두 번째 초과는 10분, 이후는 30분 휴식. 변경하면 상대방의 동의를 다시 받아요.", 13, muted).spaced())
        val focus = CheckBox(this).apply { text = "학습 시간에 이 대상 앱 쉬기"; isChecked = existing?.studyStartMinute?.let { it >= 0 } == true }
        val from = field("시작 HH:mm", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME).apply { setText(clockTime(existing?.studyStartMinute?.takeIf { it >= 0 } ?: 1200)) }
        val to = field("종료 HH:mm", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME).apply { setText(clockTime(existing?.studyEndMinute?.takeIf { it >= 0 } ?: 1260)) }
        val risk = CheckBox(this).apply { text = "유해 가능성 관측 시 단계적 휴식"; isChecked = existing?.protectRisk ?: false }
        form.addView(focus); form.addView(from); form.addView(to); form.addView(risk)
        form.addView(text("학습 보호는 이 대상 앱 전체에 적용해요. 공부에 필요한 앱은 개별 대상으로 선택하지 마세요. AI 위험 추정은 틀릴 수 있고, 자동 즉시 차단하지 않아요.", 13, muted).spaced())
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle(if (byParent) "부모의 약속 제안" else "자녀의 변경 제안").setView(scroll).setPositiveButton("제안 저장", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minutes = limit.text.toString().toLongOrNull()
            val graceMinutes = grace.text.toString().toLongOrNull()
            if (minutes == null || minutes !in 1..1440 || graceMinutes == null || graceMinutes !in 1..60) { toast("사용시간은 1~1440분, 유예는 1~60분으로 입력해 주세요"); return@setOnClickListener }
            val app = apps[spinner.selectedItemPosition]
            val start = parseClock(from.text.toString()); val end = parseClock(to.text.toString())
            if (focus.isChecked && (start == null || end == null || start == end)) { toast("서로 다른 시작·종료 시간을 HH:mm으로 입력해 주세요"); return@setOnClickListener }
            store.saveRule(Rule(app.first, app.second, minutes * 60, graceMinutes * 60, byParent, !byParent,
                if (focus.isChecked) start!! else -1, if (focus.isChecked) end!! else -1, risk.isChecked))
            dialog.dismiss(); render()
        } }
        dialog.show()
    }

    private fun explain(observation: Observation) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        val purposes = Purpose.entries.toList()
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, purposes.map { it.label }); setSelection(purposes.indexOf(observation.purpose)); minimumHeight = dp(48) }
        val note = field("선택: 나의 설명 (최대 240자)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { setText(observation.note); maxLines = 4 }
        form.addView(spinner); form.addView(note)
        form.addView(text("수정한 분류는 자동 추정과 구분해요. 설명을 적어도 사용 제한이 자동 해제되지는 않아요.", 13, muted).spaced())
        AlertDialog.Builder(this).setTitle("어떤 시간이었나요?").setView(form).setPositiveButton("저장") { _, _ -> store.correct(observation.id, purposes[spinner.selectedItemPosition], note.text.toString()); render() }.setNegativeButton("취소", null).show()
    }

    private fun showSharedReport(parent: Boolean) {
        val projection = ReportProjector.project(store.observations(), store.sharing)
        val body = buildString {
            append("최근 7일 · 자녀가 선택한 항목만 공개\n\n")
            if (projection.purposeCounts.isNotEmpty()) {
                append("사용 맥락 · 관측 건수\n")
                projection.purposeCounts.forEach { (label, count) -> append("$label  ${count}건\n") }; append("\n")
            }
            if (projection.interestCounts.isNotEmpty()) {
                append("공유한 관심 주제\n")
                projection.interestCounts.forEach { (label, count) -> append("$label  ${count}건\n") }; append("\n")
                append("함께 이야기해 봐요\n")
                ConversationCoach.questions(projection.interestCounts.keys).forEach { append("• $it\n") }
                val cutoff = System.currentTimeMillis() - 7L * 86400000
                val prior = ReportProjector.project(store.observations(cutoff - 7L * 86400000, cutoff), store.sharing)
                if (prior.interestCounts.isNotEmpty()) {
                    append("\n지난주 → 이번 주 · 관측 건수\n")
                    (prior.interestCounts.keys + projection.interestCounts.keys).sorted().forEach { topic -> append("$topic: ${prior.interestCounts[topic] ?: 0} → ${projection.interestCounts[topic] ?: 0}건\n") }
                }
            }
            if (projection.notes.isNotEmpty()) { append("자녀의 설명\n"); projection.notes.take(20).forEach { append("• $it\n") } }
            if (projection.purposeCounts.isEmpty() && projection.interestCounts.isEmpty() && projection.notes.isEmpty()) append("현재 공유된 내용이 없어요.\n")
            append("\n자동 분석은 AI 맥락 추정이며, 실제 사용 의도나 안전 여부를 판정하지 않아요.")
        }
        val scroll = ScrollView(this).apply { addView(text(body, 15, ink).apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }) }
        val dialog = AlertDialog.Builder(this).setTitle(if (parent) "부모에게 공유된 리포트" else "공유 화면 미리보기").setView(scroll).setPositiveButton("닫기", null).create()
        privateDialog = dialog; dialog.show()
    }

    private fun parentGate(then: () -> Unit) {
        if (!store.hasParentPin) { configurePin(then); return }
        if (store.pinWaitSeconds() > 0) { toast("${store.pinWaitSeconds()}초 뒤 다시 시도해 주세요"); return }
        val input = field("보호자 PIN 6자리", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val dialog = AlertDialog.Builder(this).setTitle("보호자 확인").setView(input).setPositiveButton("확인", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (store.verifyParentPin(input.text.toString())) { dialog.dismiss(); then() }
            else { input.text.clear(); toast(if (store.pinWaitSeconds() > 0) "잠시 후 다시 시도해 주세요" else "PIN이 맞지 않아요") }
        } }
        privateDialog = dialog; dialog.show()
    }
    private fun configurePin(then: () -> Unit) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        form.addView(text("보호자가 직접 설정해 주세요. 현재는 이 기기의 약속 승인과 부모 보기를 보호하는 PIN이며, 원격 계정 인증은 아니에요.", 14, muted).spaced())
        val first = field("새 PIN 6자리", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val second = field("PIN 다시 입력", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        form.addView(first); form.addView(second)
        val dialog = AlertDialog.Builder(this).setTitle("보호자 PIN 설정").setView(form).setPositiveButton("저장", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = first.text.toString()
            if (!value.matches(Regex("[0-9]{6}")) || value != second.text.toString()) { toast("같은 6자리 숫자를 두 번 입력해 주세요"); return@setOnClickListener }
            store.setParentPin(value); dialog.dismiss(); render(); then()
        } }
        privateDialog = dialog; dialog.show()
    }

    @Deprecated("Activity result API used for the minimal platform-only client")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val image = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse { toast("이미지를 읽을 수 없어요"); recognizer.close(); return }
        toast("기기에서 한국어 OCR을 실행하고 있어요")
        recognizer.process(image).addOnSuccessListener { result ->
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            val ocrText = result.text
            if (ocrText.isBlank()) { showOcrResult(ocrText, "인식할 수 있는 글자가 없어요."); return@addOnSuccessListener }
            val token = RelayCredential(this).read()
            if (!store.cloudConsent || !CloudClient.validEndpoint(store.endpoint) || token.isBlank()) {
                showOcrResult(ocrText, "관심 주제와 판단 이유를 보려면 분석 서버 연결과 AI 맥락 분석 동의가 필요해요.")
                return@addOnSuccessListener
            }
            toast("OCR 완료 · 로컬 모델이 관심 주제와 맥락을 분석하고 있어요")
            Thread {
                val analyzed = runCatching { CloudClient.analyzeDetailed(store.endpoint, token, ScreenPrivacy.redact(ocrText)) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    analyzed.fold(
                        onSuccess = { detailed ->
                            val topics = detailed.analysis.interests.sorted().joinToString().ifEmpty { "근거 부족" }
                            showOcrResult(ocrText, "AI가 본 맥락: ${detailed.analysis.purpose.label}\n관심 주제: $topics\n판단 이유: ${detailed.reason}\n확신도: ${detailed.analysis.certainty}")
                        },
                        onFailure = { error -> showOcrResult(ocrText, "AI 분석 실패: ${CloudClient.explain(error)}") }
                    )
                }
            }.start()
        }.addOnFailureListener { toast("OCR 실패: 이미지를 다시 선택해 주세요") }.addOnCompleteListener { recognizer.close() }
    }

    private fun showOcrResult(ocrText: String, analysisText: String) {
        val body = "인식 글자 ${ocrText.length}자\n\n${ocrText.take(2000)}\n\n$analysisText\n\nOCR 원문과 이 확인 결과는 기록에 저장하지 않아요."
        val scroll = ScrollView(this).apply { addView(text(body, 14, ink).apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }) }
        privateDialog?.dismiss()
        privateDialog = AlertDialog.Builder(this).setTitle("실제 OCR 및 AI 분석 결과").setView(scroll).setPositiveButton("닫기", null).show()
    }

    private fun clockTime(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    private fun parseClock(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }
    private fun analysisReadiness(): String = when {
        store.selectedApps.isEmpty() -> "아직 분석할 앱이 없어요 · 설정에서 앱을 선택해 주세요"
        !store.consent -> "화면 수집 동의가 필요해요 · 분석 시작하기를 눌러 주세요"
        !accessibilityEnabled() -> "접근성 연결이 꺼져 있어요 · Android 설정에서 가루를 켜 주세요"
        store.paused -> "분석이 일시정지 상태예요 · 분석 시작하기를 눌러 주세요"
        RelayCredential(this).read().isBlank() -> "서버 연결 토큰이 없어요 · 설정에서 분석 서버를 연결해 주세요"
        !store.cloudConsent -> "외부 AI 분석 동의가 필요해요 · 설정에서 부모와 자녀가 함께 동의해 주세요"
        else -> store.status + "\n선택한 앱의 글자 있는 화면을 사용하면 분석해요."
    }
    private fun configureRelay() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val endpoint = field("HTTPS 분석 서버 주소", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { setText(store.endpoint) }
        val token = field("서버 연결 토큰 (AI 제공업체 키 아님)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        form.addView(endpoint); form.addView(token)
        form.addView(text("서버에서 발급한 연결 토큰을 입력해 주세요. 연결 변경 후 외부 분석에 다시 동의해야 해요.", 13, muted).spaced())
        val dialog = AlertDialog.Builder(this).setTitle("분석 서버 연결").setView(form).setPositiveButton("저장", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = endpoint.text.toString().trim().trimEnd('/')
            val value = token.text.toString().trim()
            if (!CloudClient.validEndpoint(url) || value.length !in 32..512 || value.any { it.isWhitespace() } || (value.startsWith("sk-") || value.startsWith("AQ.") || value.startsWith("AIza"))) {
                toast("HTTPS 주소와 32자 이상의 서버 연결 토큰을 확인해 주세요"); return@setOnClickListener
            }
            if (runCatching { RelayCredential(this).save(value) }.isFailure) { toast("연결 정보를 안전하게 저장하지 못했어요"); return@setOnClickListener }
            store.endpoint = url; dialog.dismiss(); render()
            store.status = "서버 연결 확인 중 · 처음에는 최대 90초 걸릴 수 있어요"
            Thread {
                val result = runCatching { CloudClient.checkConnection(url, value) }
                runOnUiThread {
                    if (store.endpoint != url) return@runOnUiThread
                    store.status = result.fold({ "서버 인증 연결 완료 · 외부 분석 동의 후 선택한 앱을 사용해 주세요" }, { CloudClient.explain(it) })
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this).setTitle("서버 연결 확인").setMessage(store.status + "\n이 검사는 서버 인증 확인이며 로컬 Qwen3 분석 성공 검사는 아니에요.").setPositiveButton("확인", null).show()
                    }
                }
            }.start()
        } }
        privateDialog = dialog; dialog.show()
    }

    private fun consentCloud() {
        if (!CloudClient.validEndpoint(store.endpoint) || RelayCredential(this).read().isBlank()) { toast("분석 서버 연결을 먼저 설정해 주세요"); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        form.addView(text("수신 서버: ${store.endpoint}\n\n화면에서 읽은 텍스트의 연락처·이메일 등 일부 식별정보를 지운 뒤 이 HTTPS 중계 서버를 거쳐 연결된 내 PC의 Qwen3로 보내 맥락을 추정해요. 이름이나 대화 내용이 남을 수 있으며 완전한 익명화를 보장하지 않아요. 원본 이미지는 보내지 않아요.\n\n앱과 중계 서버는 OCR 원문을 저장하지 않고 목적·관심사·위험 가능성 요약을 기기에 최대 62일 보관해요. 중계 서버는 전달 중 텍스트를 메모리에만 잠시 보관하고 처리 완료 또는 대기 만료 시 지워요. AI 분석은 연결된 PC에서 실행하며 OpenAI나 Google AI에 보내지 않아요. PC가 켜져 있고 로컬 분석 프로그램이 실행 중이어야 해요. 언제든 동의를 철회할 수 있어요.", 14, ink))
        val parent = CheckBox(this).apply { text = "보호자가 외부 텍스트 분석에 동의해요" }
        val child = CheckBox(this).apply { text = "자녀가 외부 텍스트 분석에 동의해요" }
        form.addView(parent); form.addView(child)
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("외부 AI 분석 동의").setView(scroll).setPositiveButton("동의", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!parent.isChecked || !child.isChecked) { toast("두 분의 동의가 모두 필요해요"); return@setOnClickListener }
            store.cloudConsent = true; dialog.dismiss(); render()
        } }
        dialog.show()
    }

    private fun accessibilityEnabled(): Boolean = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any { it.startsWith("$packageName/") } == true
    private fun appLabel(pkg: String): String = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun time(at: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 20, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); strokeColor?.let { setStroke(dp(1), it) }
    }
    private fun text(value: String, size: Int = 14, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun TextView.spaced(): TextView = apply { setPadding(0, dp(12), 0, dp(8)) }
    private fun title(value: String, subtitle: String, icon: String = "⌛") {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(20))
            background = rounded(accent, 28)
            elevation = dp(4).toFloat()
        }
        banner.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("☰", 27, Color.WHITE, true).apply { contentDescription = "메뉴" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("♧", 25, Color.WHITE).apply { gravity = Gravity.CENTER; contentDescription = "알림" }, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = ""; background = rounded(Color.rgb(225, 228, 232), 24); contentDescription = "프로필"
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
        })
        banner.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(value, 27, Color.WHITE, true))
                addView(text(subtitle, 13, Color.WHITE).apply { setPadding(0, dp(5), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 31f; gravity = Gravity.CENTER
                background = rounded(Color.rgb(255, 224, 166), 34, Color.WHITE)
            }, LinearLayout.LayoutParams(dp(68), dp(68)))
        })
        content.addView(banner, LinearLayout.LayoutParams(-1, -2))
    }
    private fun renderPreservingScroll() {
        val y = (content.parent as? ScrollView)?.scrollY ?: 0
        render()
        (content.parent as? ScrollView)?.post { (content.parent as? ScrollView)?.scrollTo(0, y) }
    }
    private fun duration(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
    }
    private fun appBadge(pkg: String): View = runCatching {
        ImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(pkg))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.WHITE, 15)
            contentDescription = "${appLabel(pkg)} 아이콘"
        } as View
    }.getOrElse {
        TextView(this).apply {
            text = "📱"; textSize = 27f; gravity = Gravity.CENTER; background = rounded(Color.WHITE, 15)
            contentDescription = "앱 아이콘"
        }
    }
    private fun categoryUsageRows(usage: Map<String, Long>, observations: List<Observation>): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val byApp = observations.groupBy { it.app }
        val purposeByApp = byApp.mapValues { (_, rows) ->
            rows.groupingBy { it.purpose }.eachCount().maxByOrNull { it.value }?.key ?: Purpose.UNKNOWN
        }
        val apps = (usage.keys + purposeByApp.keys).distinct()
        val grouped = apps.groupBy { purposeByApp[it] ?: Purpose.UNKNOWN }.entries.sortedByDescending { (_, packages) ->
            packages.sumOf { usage[it] ?: 0L }.takeIf { it > 0 } ?: packages.sumOf { byApp[it]?.size?.toLong() ?: 0L }
        }
        if (grouped.isEmpty()) {
            layout.addView(text("사용 기록이 쌓이면 카테고리별 대표 앱을 보여드려요.", 13, muted).spaced())
            return layout
        }
        grouped.take(4).forEach { (purpose, packages) ->
            val topApps = packages.sortedWith(compareByDescending<String> { usage[it] ?: 0L }.thenByDescending { byApp[it]?.size ?: 0 }).take(2)
            val seconds = packages.sumOf { usage[it] ?: 0L }
            layout.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(5))
                val icons = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                topApps.forEach { pkg -> icons.addView(appBadge(pkg), LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(4) }) }
                addView(icons, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(purpose.label, 15, ink, true))
                    addView(text(topApps.joinToString(", ") { appLabel(it) }, 11, muted).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(if (seconds > 0) duration(seconds) else "${packages.sumOf { byApp[it]?.size ?: 0 }}건", 13, accentDark, true))
            })
        }
        return layout
    }
    private fun sectionHeading(label: String, badge: String, badgeColor: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(label, 18, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip(badge, badgeColor))
    }
    private fun reportRankRow(rank: Int, topic: String, detail: String, apps: List<String>): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(text("$rank.", 16, ink, true), LinearLayout.LayoutParams(dp(28), -2))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(topic, 16, ink, true))
            addView(text(detail, 11, muted).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        apps.take(2).forEach { pkg -> addView(appBadge(pkg), LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(4) }) }
    }
    private fun chip(label: String, color: Int) = text(label, 11, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(color, 14)
    }
    private fun foot(value: String) { content.addView(text(value, 12, muted).spaced()) }
    private fun empty(heading: String, body: String) { card { addView(text(heading, 18, ink, true)); addView(text(body, 14, muted).spaced()) } }
    private fun card(color: Int = pale, body: LinearLayout.() -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(18)); background = rounded(color); elevation = dp(1).toFloat() }
        layout.body(); content.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(2) })
    }
    private fun LinearLayout.divider() {
        addView(View(this@MainActivity).apply { setBackgroundColor(warmLine) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(15); bottomMargin = dp(15) })
    }
    private fun LinearLayout.addUsageRow(pkg: String, seconds: Long) {
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            addView(appBadge(pkg), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(appLabel(pkg), 15, ink, true))
                addView(text(duration(seconds), 12, muted).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    private fun LinearLayout.row(left: String, right: String) {
        val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        row.addView(text(left, 14, ink), LinearLayout.LayoutParams(0, -2, 1f)); row.addView(text(right, 14, green, true)); addView(row)
    }
    private fun LinearLayout.action(label: String, secondary: Boolean = false, click: () -> Unit) {
        val button = Button(this@MainActivity).apply {
            text = label; textSize = 14f; isAllCaps = false; minHeight = dp(50)
            setTextColor(if (secondary) accentDark else Color.WHITE)
            background = if (secondary) rounded(Color.WHITE, 22, Color.rgb(239, 214, 169)) else rounded(accent, 22)
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { click() }
        }
        addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun LinearLayout.check(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
        addView(CheckBox(this@MainActivity).apply { text = label; textSize = 14f; minHeight = dp(48); isChecked = checked; setTextColor(ink); setOnCheckedChangeListener { _, value -> changed(value) } })
    }
    private fun field(hintText: String, input: Int) = EditText(this).apply { hint = hintText; inputType = input; textSize = 16f; minHeight = dp(50); setPadding(dp(12), dp(8), dp(12), dp(8)) }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
