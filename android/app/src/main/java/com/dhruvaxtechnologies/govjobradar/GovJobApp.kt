package com.dhruvaxtechnologies.govjobradar

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import com.dhruvaxtechnologies.govjobradar.admin.AdminRepo
import com.dhruvaxtechnologies.govjobradar.data.Prefs
import com.dhruvaxtechnologies.govjobradar.data.Repository
import com.dhruvaxtechnologies.govjobradar.report.FirebaseBridge
import com.dhruvaxtechnologies.govjobradar.report.Reporter
import com.dhruvaxtechnologies.govjobradar.sync.Notifier
import com.dhruvaxtechnologies.govjobradar.sync.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GovJobApp : Application() {
    lateinit var prefs: Prefs
    lateinit var repo: Repository
    lateinit var reporter: Reporter
    lateinit var admin: AdminRepo
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        reporter = Reporter(this, prefs)
        reporter.installCrashHandler()
        repo = Repository(this, prefs)
        admin = AdminRepo(this)
        FirebaseBridge.init(this)
        Notifier.createChannels(this)
        Scheduler.scheduleNightly(this, prefs.profile.value.wifiOnly, ExistingPeriodicWorkPolicy.KEEP)
        scope.launch {
            reporter.sendPendingCrash()
            reporter.flush()
        }
    }

    /**
     * Called whenever the app comes to the screen: catches the "should have happened but
     * didn't" cases on the phone and reports them to Admin > Errors.
     */
    fun healthCheckOnOpen() = scope.launch {
        if (!prefs.profile.value.onboarded) return@launch
        val state = Scheduler.nightlyState(this@GovJobApp)
        if (state == null || state == WorkInfo.State.CANCELLED || state == WorkInfo.State.FAILED) {
            Scheduler.scheduleNightly(this@GovJobApp, prefs.profile.value.wifiOnly)
            reporter.report("APP_WORK_MISSING", "Nightly update job was '$state' on this phone; re-scheduled.", "warning", minGapHours = 72)
        }
        val now = System.currentTimeMillis()
        val ok = prefs.lastSyncOk
        if (ok > 0 && now - ok > 72 * 3_600_000L) {
            reporter.report("APP_SYNC_STALE",
                "No successful nightly update for ${(now - ok) / 3_600_000L} h (work state: $state, " +
                    "fail streak: ${prefs.syncFailStreak}). Possibly battery optimisation.", "warning", minGapHours = 72)
        }
        // Foreground catch-up when the night update was missed (silent; no notification).
        if (now - ok > 20 * 3_600_000L) {
            val r = repo.refresh()
            if (r !is com.dhruvaxtechnologies.govjobradar.data.SyncResult.Failed) {
                prefs.lastSyncOk = System.currentTimeMillis()
                prefs.syncFailStreak = 0
                Scheduler.syncReminders(this@GovJobApp)
            }
        }
    }
}
