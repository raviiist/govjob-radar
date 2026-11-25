package com.dhruvaxtechnologies.govjobradar.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.data.Eligibility
import com.dhruvaxtechnologies.govjobradar.data.Job
import com.dhruvaxtechnologies.govjobradar.data.SyncResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Timing (phone local time, India):
 *  ~02:11 server crawl starts → feed published by ~04:30
 *  04:30–06:30 phone downloads silently (no notification for the download itself)
 *  08:00 one morning summary if something new matches the user
 */
object Scheduler {
    const val NIGHTLY = "nightly-sync"
    private const val DIGEST = "morning-digest"

    private fun delayUntil(time: LocalTime, from: LocalDateTime = LocalDateTime.now()): Duration {
        var target = from.toLocalDate().atTime(time)
        if (!target.isAfter(from.plusMinutes(1))) target = target.plusDays(1)
        return Duration.between(from, target)
    }

    fun scheduleNightly(context: Context, wifiOnly: Boolean, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        // 24 h period, allowed to run in the last 2 h of each period → window starts ~04:30.
        val req = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(delayUntil(LocalTime.of(4, 30)).minusHours(22).let { if (it.isNegative) it.plusHours(24) else it })
            .addTag(NIGHTLY)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NIGHTLY, policy, req)
    }

    fun scheduleDigest(context: Context) {
        val now = LocalDateTime.now()
        val at = if (now.toLocalTime().isBefore(LocalTime.of(8, 0))) delayUntil(LocalTime.of(8, 0), now)
                 else Duration.ofMinutes(1)   // already morning/daytime: show it now
        val req = OneTimeWorkRequestBuilder<DigestWorker>().setInitialDelay(at).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DIGEST, ExistingWorkPolicy.KEEP, req)
    }

    fun scheduleReminder(context: Context, job: Job, daysBefore: Int) {
        val last = job.last ?: return
        val at = last.minusDays(daysBefore.toLong()).atTime(9, 0)
        val delay = Duration.between(LocalDateTime.now(), at)
        val name = "rem_${job.id}_$daysBefore"
        if (delay.isNegative) { WorkManager.getInstance(context).cancelUniqueWork(name); return }
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf("id" to job.id, "date" to job.lastDate, "days" to daysBefore))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelReminders(context: Context, id: String) {
        listOf(1, 3).forEach { WorkManager.getInstance(context).cancelUniqueWork("rem_${id}_$it") }
    }

    /** Make reminders match saved jobs and their current last dates (dates can be extended). */
    fun syncReminders(context: Context): List<Job> {
        val app = context.applicationContext as GovJobApp
        val saved = app.prefs.saved.value
        val known = app.prefs.reminderDates.associate { it.substringBefore('=') to it.substringAfter('=') }
        val next = mutableSetOf<String>()
        val extendedSaved = mutableListOf<Job>()
        for (id in saved) {
            val job = app.repo.job(id) ?: continue
            val date = job.lastDate ?: continue
            if (known[id] != date) {
                if (known[id] != null && job.extended) extendedSaved += job
                scheduleReminder(context, job, 3)
                scheduleReminder(context, job, 1)
            }
            next += "$id=$date"
        }
        (known.keys - saved).forEach { cancelReminders(context, it) }
        app.prefs.reminderDates = next
        return extendedSaved
    }

    fun nightlyState(context: Context): WorkInfo.State? = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(NIGHTLY).get().firstOrNull()?.state
    }.getOrNull()
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GovJobApp
        val prefs = app.prefs
        prefs.lastSyncAttempt = System.currentTimeMillis()
        return when (val r = app.repo.refresh()) {
            is SyncResult.Failed -> {
                prefs.syncFailStreak = prefs.syncFailStreak + 1
                if (r.code != "APP_SYNC_NETWORK" || prefs.syncFailStreak >= 3) {
                    app.reporter.report(if (r.code == "APP_SYNC_NETWORK") "APP_SYNC_FAILED" else r.code,
                        "Nightly update failed (${prefs.syncFailStreak} in a row): ${r.message}",
                        if (r.code == "APP_SYNC_NETWORK") "warning" else "error")
                }
                if (runAttemptCount < 2 && r.code == "APP_SYNC_NETWORK") Result.retry() else Result.success()
            }
            is SyncResult.NoChange -> { onSuccess(app, emptySet()); Result.success() }
            is SyncResult.Updated -> { onSuccess(app, r.newIds); Result.success() }
        }
    }

    private suspend fun onSuccess(app: GovJobApp, newIds: Set<String>) {
        val prefs = app.prefs
        prefs.lastSyncOk = System.currentTimeMillis()
        prefs.syncFailStreak = 0
        // Server-side check from the phone's point of view: is the feed itself fresh?
        app.repo.feedAge()?.let { age ->
            if (age.toHours() > 48) app.reporter.report("APP_FEED_OLD",
                "Phone downloaded the feed but it was generated ${age.toHours()} h ago — publish workflow may not be running.",
                "warning", minGapHours = 24)
        }
        val extended = Scheduler.syncReminders(app)
        val p = prefs.profile.value
        val wanted = if (!p.notifyNew) emptySet() else newIds.filter { id ->
            val j = app.repo.job(id) ?: return@filter false
            val stateOk = j.level == "central" || p.homeState == null || j.state == p.homeState
            val kindOk = !j.isDeputation || p.govtEmployee
            stateOk && kindOk && (!p.notifyOnlyEligible || Eligibility.likelyEligible(j, p) != false)
        }.toSet()
        val ext = extended.map { "ext:" + it.id }
        if (wanted.isNotEmpty() || ext.isNotEmpty()) {
            prefs.pendingDigest = prefs.pendingDigest + wanted + ext
            Scheduler.scheduleDigest(app)
        }
        app.reporter.flush()
    }
}

class DigestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GovJobApp
        val ids = app.prefs.pendingDigest
        app.prefs.pendingDigest = emptySet()
        val extended = ids.filter { it.startsWith("ext:") }.mapNotNull { app.repo.job(it.removePrefix("ext:")) }
        val fresh = ids.filterNot { it.startsWith("ext:") }.mapNotNull { app.repo.job(it) }
            .filter { (it.daysLeft() ?: 0) >= 0 }
        try {
            if (extended.isNotEmpty()) {
                Notifier.post(app, 2001, Notifier.CH_REMIND,
                    if (extended.size == 1) "Last date extended" else "${extended.size} saved jobs: last date extended",
                    extended.joinToString("\n") { "${it.title} → ${it.lastDate}" },
                    lines = extended.map { "${it.title} → ${it.lastDate}" },
                    jobId = extended.singleOrNull()?.id, tab = "my")
            }
            if (fresh.isNotEmpty()) {
                Notifier.post(app, 2000, Notifier.CH_NEW,
                    if (fresh.size == 1) "1 new government job for you" else "${fresh.size} new government jobs for you",
                    fresh.first().title,
                    lines = fresh.map { "${it.organisation}: ${it.title}" },
                    jobId = fresh.singleOrNull()?.id, tab = "new")
            }
        } catch (e: Exception) {
            app.reporter.report("APP_NOTIFY_FAILED", "Morning summary could not be shown: ${e.javaClass.simpleName}: ${e.message}")
        }
        return Result.success()
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GovJobApp
        val id = inputData.getString("id") ?: return Result.success()
        val date = inputData.getString("date")
        val days = inputData.getInt("days", 1)
        val job = app.repo.job(id) ?: return Result.success()
        if (id !in app.prefs.saved.value || job.lastDate != date) return Result.success()  // unsaved or date changed
        try {
            Notifier.post(app, id.hashCode(), Notifier.CH_REMIND,
                if (days == 1) "Last date tomorrow" else "Last date in $days days",
                "${job.title} — ${job.organisation}. Last date ${job.last ?: job.lastDate}.", jobId = id)
        } catch (e: Exception) {
            app.reporter.report("APP_REMINDER_FAILED", "Reminder for $id failed: ${e.message}")
        }
        return Result.success()
    }
}
