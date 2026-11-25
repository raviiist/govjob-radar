package com.dhruvaxtechnologies.govjobradar.report

import android.content.Context
import android.os.Build
import com.dhruvaxtechnologies.govjobradar.BuildConfig
import com.dhruvaxtechnologies.govjobradar.data.Prefs
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Sends "something that should have happened did not happen" reports to Firestore
 * (collection app_reports), where they appear in Admin > Errors.
 * Anonymous: no name, email or profile data is ever included.
 */
class Reporter(private val context: Context, private val prefs: Prefs) {
    @Serializable
    data class Report(val code: String, val message: String, val severity: String, val jobId: String? = null,
                      val at: String = Instant.now().toString())

    private val queueFile = File(context.filesDir, "report-queue.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Queue a report (rate-limited) and try to send everything queued. */
    suspend fun report(code: String, message: String, severity: String = "error", jobId: String? = null,
                       minGapHours: Int = 12) {
        val now = System.currentTimeMillis()
        if (code != "USER_REPORT" && now - prefs.lastReportAt(code) < minGapHours * 3_600_000L) return
        val today = LocalDate.now().toString()
        val (day, count) = prefs.reportsToday
        val n = if (day == today) count else 0
        if (n >= 15) return
        prefs.reportsToday = today to n + 1
        prefs.markReported(code, now)
        enqueue(Report(code, message.take(3800), severity, jobId))
        flush()
    }

    @Synchronized private fun readQueue(): MutableList<Report> = try {
        if (queueFile.exists()) json.decodeFromString(ListSerializer(Report.serializer()), queueFile.readText()).toMutableList()
        else mutableListOf()
    } catch (e: Exception) { mutableListOf() }

    @Synchronized private fun writeQueue(list: List<Report>) {
        queueFile.writeText(json.encodeToString(ListSerializer(Report.serializer()), list.takeLast(30)))
    }

    private fun enqueue(r: Report) = writeQueue(readQueue().apply { add(r) })

    /** Send queued reports. Returns false if Firebase is not configured or offline. */
    suspend fun flush(): Boolean {
        if (!FirebaseBridge.init(context)) return false
        val queue = readQueue()
        if (queue.isEmpty()) return true
        return try {
            val auth = FirebaseBridge.auth() ?: return false
            val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: return false
            val db = FirebaseBridge.db() ?: return false
            val sent = mutableListOf<Report>()
            for (r in queue) {
                val doc = hashMapOf<String, Any?>(
                    "code" to r.code.take(40), "message" to r.message, "severity" to r.severity,
                    "app_version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    "android" to "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}",
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}".take(80),
                    "created_at" to r.at, "uid" to user.uid, "resolved" to false,
                )
                if (r.jobId != null) doc["job_id"] = r.jobId
                db.collection("app_reports").add(doc).await()
                sent += r
            }
            writeQueue(readQueue().filterNot { it in sent })
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- crashes: saved on the phone, sent at next start ----
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = e.stackTraceToString().lines().take(40).joinToString("\n")
                File(context.filesDir, "last-crash.txt").writeText("Thread ${t.name}\n$trace")
            } catch (_: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    suspend fun sendPendingCrash() {
        val f = File(context.filesDir, "last-crash.txt")
        if (!f.exists()) return
        val text = f.readText()
        f.delete()
        report("APP_CRASH", text, "critical", minGapHours = 1)
    }
}
