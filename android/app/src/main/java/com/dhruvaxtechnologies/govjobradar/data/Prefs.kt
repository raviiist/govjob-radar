package com.dhruvaxtechnologies.govjobradar.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

data class Profile(
    val onboarded: Boolean = false,
    val govtEmployee: Boolean = false,
    val homeState: String? = null,
    val dob: LocalDate? = null,
    val qualification: String? = null,
    val category: String = "General",
    val pwd: Boolean = false,
    val exServiceman: Boolean = false,
    val payLevel: Int? = null,
    val notifyNew: Boolean = true,
    val notifyOnlyEligible: Boolean = false,
    val wifiOnly: Boolean = false,
)

/** Everything kept on the phone. Nothing here is uploaded. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("gjr", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<Profile> = _profile

    private val _saved = MutableStateFlow(sp.getStringSet("saved", emptySet())!!.toSet())
    val saved: StateFlow<Set<String>> = _saved

    private fun readProfile() = Profile(
        onboarded = sp.getBoolean("onboarded", false),
        govtEmployee = sp.getBoolean("govt", false),
        homeState = sp.getString("state", null),
        dob = sp.getString("dob", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        qualification = sp.getString("qual", null),
        category = sp.getString("cat", "General")!!,
        pwd = sp.getBoolean("pwd", false),
        exServiceman = sp.getBoolean("esm", false),
        payLevel = sp.getInt("pay", 0).takeIf { it in 1..18 },
        notifyNew = sp.getBoolean("notify", true),
        notifyOnlyEligible = sp.getBoolean("notify_elig", false),
        wifiOnly = sp.getBoolean("wifi_only", false),
    )

    fun saveProfile(p: Profile) {
        sp.edit()
            .putBoolean("onboarded", p.onboarded).putBoolean("govt", p.govtEmployee)
            .putString("state", p.homeState).putString("dob", p.dob?.toString())
            .putString("qual", p.qualification).putString("cat", p.category)
            .putBoolean("pwd", p.pwd).putBoolean("esm", p.exServiceman).putInt("pay", p.payLevel ?: 0)
            .putBoolean("notify", p.notifyNew).putBoolean("notify_elig", p.notifyOnlyEligible)
            .putBoolean("wifi_only", p.wifiOnly)
            .apply()
        _profile.value = p
    }

    fun toggleSaved(id: String) {
        val s = _saved.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        sp.edit().putStringSet("saved", s).apply()
        _saved.value = s
    }

    // --- sync bookkeeping ---
    var lastSyncOk: Long
        get() = sp.getLong("last_sync_ok", 0)
        set(v) = sp.edit().putLong("last_sync_ok", v).apply()
    var lastSyncAttempt: Long
        get() = sp.getLong("last_sync_attempt", 0)
        set(v) = sp.edit().putLong("last_sync_attempt", v).apply()
    var syncFailStreak: Int
        get() = sp.getInt("sync_fail_streak", 0)
        set(v) = sp.edit().putInt("sync_fail_streak", v).apply()
    var feedEtag: String?
        get() = sp.getString("etag", null)
        set(v) = sp.edit().putString("etag", v).apply()
    var knownIds: Set<String>
        get() = sp.getStringSet("known_ids", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("known_ids", v).apply()
    var pendingDigest: Set<String>
        get() = sp.getStringSet("pending_digest", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("pending_digest", v).apply()
    /** Last date for which reminders were scheduled, per saved job: "id=2026-12-15". */
    var reminderDates: Set<String>
        get() = sp.getStringSet("reminder_dates", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("reminder_dates", v).apply()

    // --- problem-report rate limiting ---
    fun lastReportAt(code: String): Long = sp.getLong("rep_$code", 0)
    fun markReported(code: String, at: Long) = sp.edit().putLong("rep_$code", at).apply()
    var reportsToday: Pair<String, Int>
        get() = (sp.getString("rep_day", "") ?: "") to sp.getInt("rep_count", 0)
        set(v) = sp.edit().putString("rep_day", v.first).putInt("rep_count", v.second).apply()
}
