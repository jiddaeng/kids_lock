package kr.kidslock.core

enum class Purpose(val label: String) {
    STUDY("학습"), HOMEWORK("과제"), COMMUNICATION("소통"), REST("휴식"), UNKNOWN("미확인")
}

data class Analysis(val purpose: Purpose, val interests: Set<String>, val evidence: List<String>, val certainty: String)

/** Transparent on-device baseline, not a validated intent-detection or safety model. */
object ContextAnalyzer {
    private val purposeTerms = linkedMapOf(
        Purpose.HOMEWORK to listOf("과제", "숙제", "수행평가", "조별", "제출", "assignment", "homework"),
        Purpose.STUDY to listOf("수학", "영어", "과학", "강의", "문제풀이", "학습", "공부", "방정식", "교과", "lecture", "tutorial"),
        Purpose.COMMUNICATION to listOf("답장", "메시지", "대화", "채팅", "안녕", "고마워", "message", "chat"),
        Purpose.REST to listOf("게임", "브이로그", "예능", "뮤직비디오", "하이라이트", "플레이", "game", "vlog", "music")
    )
    private val topics = linkedMapOf(
        "수학" to listOf("수학", "방정식", "함수", "기하", "math"),
        "과학" to listOf("과학", "실험", "우주", "생물", "science"),
        "언어" to listOf("영어", "단어", "문법", "외국어", "english"),
        "음악" to listOf("음악", "노래", "피아노", "기타", "뮤직", "music"),
        "스포츠" to listOf("축구", "야구", "농구", "스포츠", "운동", "football"),
        "게임" to listOf("게임", "마인크래프트", "로블록스", "game", "minecraft"),
        "만들기" to listOf("그림", "만들기", "요리", "코딩", "그리기", "drawing", "coding")
    )
    private val noise = listOf("추천 동영상", "추천 콘텐츠", "관련 동영상", "광고", "sponsored", "추천 게시물")

    fun analyze(text: String): Analysis {
        val cleaned = text.lineSequence().map(String::trim)
            .filter { line -> noise.none { line.contains(it, ignoreCase = true) } }
            .joinToString(" ").lowercase().take(6000)
        val matches = purposeTerms.mapValues { (_, words) -> words.filter { cleaned.contains(it) } }
        val ranked = matches.entries.sortedByDescending { it.value.size }
        val first = ranked.first()
        // Conflicting purposes stay unknown; never infer purpose from package name alone.
        val purpose = if (first.value.isEmpty() || ranked[1].value.size == first.value.size) Purpose.UNKNOWN else first.key
        val topicMatches = topics.filterValues { words -> words.any { cleaned.contains(it) } }.keys
        val evidence = matches.values.flatten().distinct().take(5)
        return Analysis(purpose, topicMatches, evidence, if (purpose == Purpose.UNKNOWN) "근거 부족 / 복합 가능" else "키워드 기반 추정")
    }
}

data class Observation(
    val id: Long = 0,
    val at: Long,
    val app: String,
    val source: String,
    val analysis: Analysis,
    val correctedPurpose: Purpose? = null,
    val note: String = ""
) {
    val purpose: Purpose get() = correctedPurpose ?: analysis.purpose
}

data class Sharing(val purposes: Boolean = false, val interests: Set<String> = emptySet(), val notes: Boolean = false)
data class SharedReport(val purposeCounts: Map<String, Int>, val interestCounts: Map<String, Int>, val notes: List<String>)

object ReportProjector {
    fun project(rows: List<Observation>, choice: Sharing): SharedReport = SharedReport(
        if (choice.purposes) rows.groupingBy { it.purpose.label }.eachCount() else emptyMap(),
        rows.flatMap { it.analysis.interests }.filter { it in choice.interests }.groupingBy { it }.eachCount(),
        if (choice.notes) rows.map { it.note }.filter(String::isNotBlank) else emptyList()
    )
}

/** Removes exact repeated screens in a bounded observation window without storing original text. */
class SampleGate(private val quietMillis: Long = 60_000) {
    private val last = mutableMapOf<String, Pair<String, Long>>()
    fun accept(app: String, fingerprint: String, now: Long): Boolean {
        val previous = last[app]
        if (previous != null && previous.first == fingerprint && now - previous.second in 0 until quietMillis) return false
        last[app] = fingerprint to now
        return true
    }
}
