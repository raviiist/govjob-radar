package com.dhruvaxtechnologies.govjobradar.admin

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.dhruvaxtechnologies.govjobradar.BuildConfig
import com.dhruvaxtechnologies.govjobradar.report.FirebaseBridge
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.time.Instant

data class ReviewItem(
    val id: String,
    val title: String,
    val organisation: String,
    val sourceName: String,
    val status: String,
    val lastDate: String?,
    val totalVacancies: Long?,
    val sector: String,
    val level: String,
    val state: String?,
    val kind: String,
    val noticeUrl: String,
    val applyUrl: String?,
    val summary: String?,
    val advtNo: String?,
    val reasons: List<String>,
    val pendingExtensionTo: String?,
    val pendingExtensionUrl: String?,
    val confidence: Double?,
    val qualification: String?,
    val raw: Map<String, Any?>,
)

data class ErrorItem(
    val collection: String,   // errors | app_reports
    val docId: String,
    val origin: String,       // server | workflow | app | user_report
    val code: String,
    val message: String,
    val severity: String,
    val count: Long,
    val lastAt: String,
    val hint: String?,
    val runUrl: String?,
    val sourceId: String?,
    val jobId: String?,
    val device: String?,
)

data class Health(
    val server: Map<String, Any?>,
    val publish: Map<String, Any?>,
    val sources: List<Map<String, Any?>>,
    val settings: Map<String, Any?>,
)

/** Admin-only access. Firestore rules allow these reads/writes only for ADMIN_EMAIL. */
class AdminRepo(private val context: Context) {

    val isConfigured get() = FirebaseBridge.configured && BuildConfig.ADMIN_EMAIL.isNotBlank() &&
        BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    fun signedInEmail(): String? {
        if (!FirebaseBridge.init(context)) return null
        val u = FirebaseBridge.auth()?.currentUser ?: return null
        return if (u.isAnonymous) null else u.email
    }

    fun isAdmin(): Boolean = signedInEmail()?.equals(BuildConfig.ADMIN_EMAIL, ignoreCase = true) == true

    /** Google sign-in with Credential Manager, then Firebase. Needs an Activity context. */
    suspend fun signIn(activityContext: Context): Result<String> = runCatching {
        check(isConfigured) { "Admin is not set up: fill ADMIN_EMAIL, FIREBASE_* and GOOGLE_WEB_CLIENT_ID in app-config.properties" }
        FirebaseBridge.init(context)
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val res = CredentialManager.create(activityContext).getCredential(activityContext, req)
        val cred = res.credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type"
        }
        val google = GoogleIdTokenCredential.createFrom(cred.data)
        val auth = FirebaseBridge.auth()!!
        val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
        val email = user?.email ?: error("No email on this Google account")
        if (!email.equals(BuildConfig.ADMIN_EMAIL, ignoreCase = true)) {
            auth.signOut()
            error("$email is not the admin account")
        }
        email
    }

    suspend fun signOut() {
        FirebaseBridge.auth()?.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun db() = FirebaseBridge.db() ?: error("Firebase not configured")

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toReview(): ReviewItem {
        val d = data ?: emptyMap()
        val pe = d["pending_extension"] as? Map<String, Any?>
        return ReviewItem(
            id = id, title = d["title"] as? String ?: "", organisation = d["organisation"] as? String ?: "",
            sourceName = d["source_name"] as? String ?: "", status = d["status"] as? String ?: "",
            lastDate = d["last_date"] as? String, totalVacancies = (d["total_vacancies"] as? Number)?.toLong(),
            sector = d["sector"] as? String ?: "other", level = d["level"] as? String ?: "central",
            state = d["state"] as? String, kind = d["kind"] as? String ?: "recruitment",
            noticeUrl = d["notice_url"] as? String ?: "", applyUrl = d["apply_url"] as? String,
            summary = d["summary"] as? String, advtNo = d["advt_no"] as? String,
            reasons = (d["review_reasons"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            pendingExtensionTo = pe?.get("to") as? String, pendingExtensionUrl = pe?.get("notice_url") as? String,
            confidence = (d["confidence"] as? Number)?.toDouble(),
            qualification = d["qualification_summary"] as? String, raw = d,
        )
    }

    suspend fun reviewQueue(): List<ReviewItem> {
        val pending = db().collection("jobs").whereEqualTo("status", "pending_review").get().await().documents
        val ext = db().collection("jobs").whereNotEqualTo("pending_extension", null).get().await().documents
        return (pending + ext).distinctBy { it.id }.map { it.toReview() }.sortedBy { it.lastDate ?: "9999" }
    }

    suspend fun liveJobs(limit: Long = 300): List<ReviewItem> =
        db().collection("jobs").whereIn("status", listOf("live", "awaiting_extension_check", "link_broken"))
            .limit(limit).get().await().documents.map { it.toReview() }

    private fun now() = Instant.now().toString()

    suspend fun approve(item: ReviewItem, edits: Map<String, Any?> = emptyMap()) {
        val upd = mutableMapOf<String, Any?>("status" to "live", "reviewed_at" to now(), "admin_updated_at" to now(),
            "review_reasons" to emptyList<String>())
        if (edits.isNotEmpty()) { upd.putAll(edits); upd["admin_locked"] = true }
        db().collection("jobs").document(item.id).update(upd).await()
    }

    suspend fun reject(item: ReviewItem, note: String?) {
        db().collection("jobs").document(item.id).update(mapOf(
            "status" to "rejected", "review_note" to (note ?: ""), "reviewed_at" to now(), "admin_updated_at" to now(),
        )).await()
    }

    /** Remove a live job for good (not a vacancy, wrong info...). It does not come back to Review. */
    suspend fun unpublish(item: ReviewItem) {
        db().collection("jobs").document(item.id).update(mapOf(
            "status" to "rejected", "admin_updated_at" to now(), "reviewed_at" to now(),
            "review_note" to "Taken down by admin",
        )).await()
    }

    /** Items whose only open point is "new source, first week" — everything else checked out. */
    fun onlyNewSource(item: ReviewItem): Boolean = item.status == "pending_review" && item.pendingExtensionTo == null &&
        item.reasons.isNotEmpty() && item.reasons.all { it.startsWith("New source") }

    suspend fun approveAll(items: List<ReviewItem>) {
        items.chunked(400).forEach { chunk ->
            val batch = db().batch()
            chunk.forEach { batch.update(db().collection("jobs").document(it.id), mapOf(
                "status" to "live", "reviewed_at" to now(), "admin_updated_at" to now(),
                "review_reasons" to emptyList<String>())) }
            batch.commit().await()
        }
    }


    @Suppress("UNCHECKED_CAST")
    suspend fun acceptExtension(item: ReviewItem) {
        val to = item.pendingExtensionTo ?: return
        val hist = ((item.raw["extension_history"] as? List<Map<String, Any?>>) ?: emptyList()) + mapOf(
            "from" to item.lastDate, "to" to to, "notice_url" to item.pendingExtensionUrl, "found_at" to now())
        val newStatus = if (item.status == "pending_review") "pending_review" else "live"
        db().collection("jobs").document(item.id).update(mapOf(
            "last_date" to to, "extended" to true, "extension_history" to hist, "pending_extension" to null,
            "extension_checks" to 0, "status" to newStatus, "review_reasons" to emptyList<String>(),
            "admin_updated_at" to now(),
        )).await()
    }

    suspend fun rejectExtension(item: ReviewItem) {
        db().collection("jobs").document(item.id).update(mapOf(
            "pending_extension" to null, "review_reasons" to emptyList<String>(), "admin_updated_at" to now(),
        )).await()
    }

    suspend fun errors(includeResolved: Boolean = false): List<ErrorItem> {
        val server = db().collection("errors").orderBy("last_at", Query.Direction.DESCENDING).limit(200).get().await()
            .documents.mapNotNull { d ->
                if (!includeResolved && d.getBoolean("resolved") == true) return@mapNotNull null
                ErrorItem("errors", d.id, d.getString("origin") ?: "server", d.getString("code") ?: "",
                    d.getString("message") ?: "", d.getString("severity") ?: "error", d.getLong("count") ?: 1,
                    d.getString("last_at") ?: "", d.getString("hint"), d.getString("run_url"),
                    d.getString("source_id"), d.getString("job_id"), null)
            }
        val app = db().collection("app_reports").orderBy("created_at", Query.Direction.DESCENDING).limit(300).get().await()
            .documents.filter { includeResolved || it.getBoolean("resolved") != true }
        // Group phone reports by code so 100 phones with the same problem show as one line.
        val grouped = app.groupBy { it.getString("code") ?: "" }.map { (code, docs) ->
            val first = docs.first()
            ErrorItem("app_reports", docs.joinToString(",") { it.id },
                if (code == "USER_REPORT") "user_report" else "app", code,
                first.getString("message") ?: "", first.getString("severity") ?: "error", docs.size.toLong(),
                first.getString("created_at") ?: "", hintFor(code), null, null, first.getString("job_id"),
                "${first.getString("device")} · Android ${first.getString("android")} · v${first.getString("app_version")}")
        }
        val rank = mapOf("critical" to 0, "error" to 1, "warning" to 2, "info" to 3)
        return (server + grouped).sortedWith(compareBy<ErrorItem> { rank[it.severity] ?: 4 }.thenByDescending { it.lastAt })
    }

    suspend fun resolve(e: ErrorItem) {
        if (e.collection == "errors") {
            db().collection("errors").document(e.docId).update(mapOf(
                "resolved" to true, "resolved_at" to now(), "resolved_by" to "admin")).await()
        } else {
            val batch = db().batch()
            e.docId.split(",").forEach { batch.update(db().collection("app_reports").document(it), "resolved", true) }
            batch.commit().await()
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun health(): Health {
        val s = db().collection("status").document("server").get().await().data ?: emptyMap()
        val p = db().collection("status").document("publish").get().await().data ?: emptyMap()
        val src = db().collection("sources").get().await().documents.map { (it.data ?: emptyMap()) + ("id" to it.id) }
        val cfg = db().collection("config").document("settings").get().await().data ?: emptyMap()
        return Health(s, p, src, cfg)
    }

    suspend fun setSetting(key: String, value: Any) {
        db().collection("config").document("settings").set(mapOf(key to value), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    private fun hintFor(code: String): String? = when (code) {
        "APP_SYNC_FAILED" -> "Phones could not download the feed. If many phones report it, check that GitHub Pages is up."
        "APP_SYNC_STALE" -> "The phone's nightly update has not run for days (battery saver can block it). One phone = ignore; many = check the app."
        "APP_FEED_PARSE", "APP_FEED_SCHEMA" -> "Feed format problem — the server and app versions may not match. Check the last publish run."
        "APP_FEED_OLD" -> "Phones got an old feed: the publish workflow has not run. Check Actions > publish."
        "APP_CRASH" -> "The app crashed on a phone. The message holds the stack trace."
        "APP_WORK_MISSING" -> "The nightly update was not scheduled on a phone; the app re-scheduled it."
        "USER_REPORT" -> "A user says this job's information is wrong. Open the job, check the official notice, unpublish if needed."
        else -> null
    }
}
