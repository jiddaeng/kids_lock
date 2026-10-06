package kr.kidslock.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.kidslock.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class LocalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "kidslock.db", null, 2) {
    val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE observations (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, app TEXT NOT NULL, source TEXT NOT NULL, purpose TEXT NOT NULL, topics TEXT NOT NULL, evidence TEXT NOT NULL, certainty TEXT NOT NULL, corrected TEXT, note TEXT NOT NULL DEFAULT '', risk TEXT NOT NULL DEFAULT 'UNKNOWN')")
        db.execSQL("CREATE INDEX obs_at ON observations(at)")
        db.execSQL("CREATE TABLE usage (day TEXT NOT NULL, app TEXT NOT NULL, millis INTEGER NOT NULL, PRIMARY KEY(day,app))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE observations ADD COLUMN risk TEXT NOT NULL DEFAULT 'UNKNOWN'")
    }
    // A new external processing purpose requires renewed consent; old local consent is insufficient.
    var cloudConsent: Boolean
        get() = prefs.getBoolean("cloudConsentV2", false)
        set(value) { prefs.edit().putBoolean("cloudConsentV2", value).apply() }
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) { prefs.edit().putString("endpoint", value).putBoolean("cloudConsentV2", false).apply() }

    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(value) { prefs.edit().putBoolean("consent", value).apply() }
    var paused: Boolean
        get() = prefs.getBoolean("paused", true)
        set(value) { prefs.edit().putBoolean("paused", value).apply() }
    var captureMode: String
        get() = "ocr"
        set(value) { prefs.edit().putString("mode", value).apply() }
    var selectedApps: Set<String>
        get() = prefs.getStringSet("apps", emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("apps", value.toSet()).apply() }
    var status: String
        get() = prefs.getString("status", "접근성 서비스를 연결해 주세요") ?: ""
        set(value) { prefs.edit().putString("status", value).apply() }
    var sharing: Sharing
        get() = Sharing(prefs.getBoolean("sharePurposes", false), prefs.getStringSet("shareTopics", emptySet())?.toSet() ?: emptySet(), prefs.getBoolean("shareNotes", false))
        set(value) { prefs.edit().putBoolean("sharePurposes", value.purposes).putStringSet("shareTopics", value.interests).putBoolean("shareNotes", value.notes).apply() }

    @Synchronized fun insert(observation: Observation) {
        val a = observation.analysis
        writableDatabase.insertOrThrow("observations", null, ContentValues().apply {
            put("at", observation.at); put("app", observation.app); put("source", observation.source)
            put("purpose", a.purpose.name); put("topics", JSONArray(a.interests.toList()).toString())
            // Only structured analysis categories are saved; raw OCR and model prose are excluded.
            put("evidence", "[]"); put("certainty", a.certainty); put("risk", a.risk.name)
        })
        writableDatabase.delete("observations", "at < ?", arrayOf((System.currentTimeMillis() - 62L * 86400000).toString()))
        writableDatabase.delete("usage", "day < ?", arrayOf(LocalDate.now().minusDays(62).toString()))
    }
    fun observations(since: Long = System.currentTimeMillis() - 7L * 86400000, before: Long = Long.MAX_VALUE): List<Observation> {
        writableDatabase.delete("observations", "at < ?", arrayOf((System.currentTimeMillis() - 62L * 86400000).toString()))
        val rows = mutableListOf<Observation>()
        readableDatabase.query("observations", null, "at >= ? AND at < ?", arrayOf(since.toString(), before.toString()), null, null, "at DESC").use { c ->
            while (c.moveToNext()) {
                fun s(key: String) = c.getString(c.getColumnIndexOrThrow(key))
                fun l(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
                rows += Observation(l("id"), l("at"), s("app"), s("source"), Analysis(Purpose.valueOf(s("purpose")), strings(s("topics")).toSet(), strings(s("evidence")), s("certainty"), Risk.valueOf(s("risk"))), if (c.isNull(c.getColumnIndexOrThrow("corrected"))) null else Purpose.valueOf(s("corrected")), s("note"))
            }
        }
        return rows
    }
    fun correct(id: Long, purpose: Purpose, note: String) {
        writableDatabase.update("observations", ContentValues().apply { put("corrected", purpose.name); put("note", note.take(240)) }, "id = ?", arrayOf(id.toString()))
    }
    @Synchronized fun addUsage(app: String, millis: Long) {
        val day = LocalDate.now().toString()
        writableDatabase.execSQL("INSERT OR IGNORE INTO usage(day,app,millis) VALUES(?,?,0)", arrayOf(day, app))
        writableDatabase.execSQL("UPDATE usage SET millis=millis+? WHERE day=? AND app=?", arrayOf<Any>(millis, day, app))
    }
    fun usageByDay(days: Int): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        val first = LocalDate.now().minusDays((days - 1).toLong())
        for (i in 0 until days) result[first.plusDays(i.toLong()).toString()] = 0
        readableDatabase.rawQuery("SELECT day,SUM(millis) FROM usage WHERE day>=? AND day<=? GROUP BY day ORDER BY day", arrayOf(first.toString(), LocalDate.now().toString())).use { c ->
            while (c.moveToNext()) result[c.getString(0)] = c.getLong(1) / 1000
        }
        return result
    }
    fun todayUsage(): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT app,millis FROM usage WHERE day=? ORDER BY millis DESC", arrayOf(LocalDate.now().toString())).use { c -> while (c.moveToNext()) result[c.getString(0)] = c.getLong(1) / 1000 }
        return result
    }
    fun rules(): List<Rule> = runCatching {
        val array = JSONArray(prefs.getString("rules", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let { Rule(it.getString("app"), it.getString("label"), it.getLong("limit"), it.getLong("grace"), it.getBoolean("parent"), it.getBoolean("child"), it.optInt("studyStart", -1), it.optInt("studyEnd", -1), it.optBoolean("protectRisk")) } }
    }.getOrDefault(emptyList())
    fun saveRule(rule: Rule) {
        val array = JSONArray()
        (rules().filterNot { it.packageName == rule.packageName } + rule).forEach {
            array.put(JSONObject().put("app", it.packageName).put("label", it.label).put("limit", it.limitSeconds).put("grace", it.graceSeconds).put("parent", it.parentApproved).put("child", it.childApproved).put("studyStart", it.studyStartMinute).put("studyEnd", it.studyEndMinute).put("protectRisk", it.protectRisk))
        }
        prefs.edit().putString("rules", array.toString()).remove("progress:${rule.packageName}")
            .remove("progress:focus:${rule.packageName}").remove("progress:risk:${rule.packageName}")
            .remove("context:focus:${rule.packageName}").remove("context:risk:${rule.packageName}").apply()
    }
    fun contextSeconds(key: String, delta: Long): Long {
        val day = LocalDate.now().toString()
        val millis = (if (prefs.getString("contextDay:$key", "") == day) prefs.getLong("context:$key", 0) else 0) + delta
        prefs.edit().putString("contextDay:$key", day).putLong("context:$key", millis).apply()
        return millis / 1000
    }
    fun progress(app: String): RuleProgress = runCatching {
        val j = JSONObject(prefs.getString("progress:$app", "{}") ?: "{}")
        RuleProgress(j.optString("day"), j.optBoolean("warning"), j.optBoolean("grace"), j.optBoolean("recorded"), strings(j.optJSONArray("days")?.toString() ?: "[]"), j.optLong("until"), j.optLong("graceStart", -1))
    }.getOrDefault(RuleProgress())
    fun saveProgress(app: String, state: RuleProgress) {
        val j = JSONObject().put("day", state.day).put("warning", state.warningShown).put("grace", state.graceShown).put("recorded", state.violationRecorded).put("days", JSONArray(state.violationDays)).put("until", state.blockedUntil).put("graceStart", state.graceStartSeconds)
        prefs.edit().putString("progress:$app", j.toString()).apply()
    }
    val hasParentPin: Boolean get() = prefs.contains("pinHash")
    fun setParentPin(pin: String) {
        require(pin.matches(Regex("[0-9]{6}")))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("pinSalt", Base64.getEncoder().encodeToString(salt)).putString("pinHash", Base64.getEncoder().encodeToString(derive(pin, salt))).apply()
    }
    fun pinWaitSeconds(): Long = ((prefs.getLong("pinUntil", 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    fun verifyParentPin(pin: String): Boolean {
        if (!hasParentPin || pinWaitSeconds() > 0) return false
        val salt = Base64.getDecoder().decode(prefs.getString("pinSalt", ""))
        val expected = Base64.getDecoder().decode(prefs.getString("pinHash", ""))
        val valid = MessageDigest.isEqual(derive(pin, salt), expected)
        val failures = if (valid) 0 else prefs.getInt("pinFailures", 0) + 1
        prefs.edit().putInt("pinFailures", if (failures >= 5) 0 else failures).putLong("pinUntil", if (failures >= 5) System.currentTimeMillis() + 60_000 else 0).apply()
        return valid
    }
    fun clearObservations() {
        writableDatabase.delete("observations", null, null)
        sharing = Sharing()
    }
    private fun derive(pin: String, salt: ByteArray): ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded
    companion object {
        fun strings(json: String): List<String> = JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context): LocalStore = instance ?: synchronized(this) { instance ?: LocalStore(context).also { instance = it } }
    }
}
