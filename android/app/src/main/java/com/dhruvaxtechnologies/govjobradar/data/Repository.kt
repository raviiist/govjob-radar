package com.dhruvaxtechnologies.govjobradar.data

import android.content.Context
import com.dhruvaxtechnologies.govjobradar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

sealed class SyncResult {
    data class Updated(val newIds: Set<String>) : SyncResult()
    data object NoChange : SyncResult()
    data class Failed(val code: String, val message: String) : SyncResult()
}

class Repository(private val context: Context, private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val cacheFile = File(context.filesDir, "feed.json")

    private val _feed = MutableStateFlow(loadCache())
    val feed: StateFlow<Feed> = _feed

    private val base: String get() = BuildConfig.FEED_BASE_URL.trimEnd('/') + "/feed/v1/"

    private fun loadCache(): Feed = try {
        if (cacheFile.exists()) json.decodeFromString(Feed.serializer(), cacheFile.readText()) else Feed()
    } catch (e: Exception) {
        cacheFile.delete()
        Feed()
    }

    fun feedAge(): Duration? = runCatching { Duration.between(Instant.parse(_feed.value.generatedAt), Instant.now()) }
        .getOrNull()

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.string() ?: throw IOException("empty response for $url")
        }
    }

    /** Download the latest feed if it changed. Safe to call any time. */
    suspend fun refresh(): SyncResult = withContext(Dispatchers.IO) {
        if (BuildConfig.FEED_BASE_URL.isBlank()) return@withContext SyncResult.Failed("APP_CONFIG", "FEED_BASE_URL not set")
        val metaText = try { get(base + "meta.json") } catch (e: Exception) {
            return@withContext SyncResult.Failed("APP_SYNC_NETWORK", "meta.json: ${e.javaClass.simpleName}: ${e.message}")
        }
        val meta = try { json.decodeFromString(FeedMeta.serializer(), metaText) } catch (e: Exception) {
            return@withContext SyncResult.Failed("APP_FEED_PARSE", "meta.json not readable: ${e.message?.take(300)}")
        }
        if (meta.hash.isNotEmpty() && meta.hash == prefs.feedEtag && _feed.value.jobs.isNotEmpty()) {
            return@withContext SyncResult.NoChange
        }
        val body = try { get(base + "jobs.json") } catch (e: Exception) {
            return@withContext SyncResult.Failed("APP_SYNC_NETWORK", "jobs.json: ${e.javaClass.simpleName}: ${e.message}")
        }
        val feed = try { json.decodeFromString(Feed.serializer(), body) } catch (e: Exception) {
            return@withContext SyncResult.Failed("APP_FEED_PARSE", "jobs.json not readable (schema ${meta.schema}): ${e.message?.take(300)}")
        }
        if (feed.schema > SUPPORTED_SCHEMA) {
            return@withContext SyncResult.Failed("APP_FEED_SCHEMA", "feed schema ${feed.schema} is newer than this app supports ($SUPPORTED_SCHEMA) — app update needed")
        }
        val tmp = File(context.filesDir, "feed.json.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        val before = prefs.knownIds
        val ids = feed.jobs.map { it.id }.toSet()
        val newIds = if (before.isEmpty()) emptySet() else ids - before   // first sync: don't announce everything
        prefs.knownIds = ids + feed.archive.map { it.id }
        prefs.feedEtag = meta.hash
        _feed.value = feed
        SyncResult.Updated(newIds)
    }

    fun job(id: String): Job? = _feed.value.jobs.firstOrNull { it.id == id } ?: _feed.value.archive.firstOrNull { it.id == id }

    companion object { const val SUPPORTED_SCHEMA = 1 }
}
