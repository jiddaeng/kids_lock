package kr.kidslock.core

import java.security.MessageDigest

enum class Risk(val label: String) { UNKNOWN("판단 보류"), NONE("위험 단서 미관측"), POSSIBLE("유해 가능성") }

/** Closed output vocabulary: a model cannot smuggle captured sentences into persisted summaries. */
object AnalysisVocabulary {
    val topics = setOf("수학", "과학", "언어", "음악", "스포츠", "게임", "만들기", "Minecraft", "요리", "독서", "정보 검색", "영상", "친구 소통")
    val reasons = setOf(
        "교과 개념이나 문제 풀이가 화면에 나타났어요.",
        "제출하거나 작성하는 과제 수행 단서가 화면에 나타났어요.",
        "메시지나 대화 흐름이 화면에 나타났어요.",
        "게임 플레이나 조작 단서가 화면에 나타났어요.",
        "검색, 뉴스 또는 사용법 확인 단서가 화면에 나타났어요.",
        "영상, 음악 또는 오락 활동 단서가 화면에 나타났어요.",
        "휴식이나 수면 안내가 화면에 나타났어요.",
        "서로 다른 활동 단서가 함께 나타났어요.",
        "판단할 수 있는 글자가 충분하지 않았어요."
    )
    fun result(purpose: String, topics: List<String>, risk: String, confidence: String): Analysis {
        require(confidence in setOf("low", "medium", "high"))
        require(topics.size <= 5 && topics.all { it in this.topics })
        val p = Purpose.valueOf(purpose)
        val r = Risk.valueOf(risk)
        return Analysis(if (confidence == "low") Purpose.UNKNOWN else p, topics.toSet(), emptyList(),
            "로컬 Qwen3 · ${when (confidence) { "high" -> "높은 확신의 추정"; "medium" -> "추정"; else -> "근거 부족" }}", if (confidence == "low") Risk.UNKNOWN else r)
    }
}

/** Best-effort identifier removal, not a guarantee of anonymity. Original text never leaves memory. */
object ScreenPrivacy {
    fun redact(text: String): String = text.take(6000)
        .replace(Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE), "[링크]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[이메일]")
        .replace(Regex("(?<!\\d)(?:\\+82[- .]?)?0?1[016789][- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)"), "[연락처]")
        .replace(Regex("(?<!\\d)\\d{6}[- ]?[1-4]\\d{6}(?!\\d)"), "[식별번호]")
        .replace(Regex("@[\\p{L}\\p{N}_]+"), "[계정]")
        .replace(Regex("(?m)^(?:이름|성명|주소|학교|학번|계좌|비밀번호|인증번호)\\s*[:：].*$"), "[개인정보]")
        .replace(Regex("\\s+"), " ").trim()
    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Commit only a successful request. Never resend an unchanged screen merely because time passed. */
class ChangedScreenGate {
    private var app: String? = null
    private var fingerprint: String? = null
    fun changed(app: String, fingerprint: String) = this.app != app || this.fingerprint != fingerprint
    fun commit(app: String, fingerprint: String) { this.app = app; this.fingerprint = fingerprint }
    fun clear() { app = null; fingerprint = null }
}

object ConversationCoach {
    fun questions(topics: Set<String>): List<String> = topics.filter { it in AnalysisVocabulary.topics }.sorted().take(3)
        .map { "요즘 ${it}에서 어떤 점이 재미있었어? 함께 이야기해 줄래?" }
    fun message(analysis: Analysis): String = when {
        analysis.risk == Risk.POSSIBLE -> "불편한 내용이 보였다면 잠시 멈추고 믿을 만한 어른과 이야기해 봐요."
        analysis.purpose in setOf(Purpose.STUDY, Purpose.HOMEWORK, Purpose.INFORMATION) -> "무엇을 배우고 있었나요? 마무리할 시간을 함께 정해 봐요."
        analysis.purpose == Purpose.COMMUNICATION -> "대화 중이라면 친구에게 마무리 인사를 할 시간을 가져요."
        else -> "쉬는 시간도 소중해요. 다음 활동을 시작할 시간을 함께 정해 봐요."
    }
}
