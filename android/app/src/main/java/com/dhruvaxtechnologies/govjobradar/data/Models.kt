package com.dhruvaxtechnologies.govjobradar.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class Post(
    val name: String = "",
    val vacancies: Int? = null,
    @SerialName("pay_level") val payLevel: Int? = null,
    @SerialName("pay_text") val payText: String? = null,
    val qualification: String? = null,
)

@Serializable
data class Extension(
    val from: String? = null,
    val to: String? = null,
    @SerialName("notice_url") val noticeUrl: String? = null,
    @SerialName("found_at") val foundAt: String? = null,
)

@Serializable
data class Job(
    val id: String,
    val title: String = "",
    val organisation: String = "",
    @SerialName("advt_no") val advtNo: String? = null,
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val level: String = "central",
    val state: String? = null,
    val sector: String = "other",
    val kind: String = "recruitment",
    val posts: List<Post> = emptyList(),
    @SerialName("total_vacancies") val totalVacancies: Int? = null,
    @SerialName("pay_level_min") val payLevelMin: Int? = null,
    @SerialName("pay_level_max") val payLevelMax: Int? = null,
    @SerialName("min_qualification") val minQualification: String = "unknown",
    @SerialName("qualification_summary") val qualificationSummary: String? = null,
    @SerialName("age_min") val ageMin: Int? = null,
    @SerialName("age_max") val ageMax: Int? = null,
    @SerialName("age_as_on") val ageAsOn: String? = null,
    @SerialName("age_note") val ageNote: String? = null,
    @SerialName("fee_text") val feeText: String? = null,
    @SerialName("apply_mode") val applyMode: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("last_date") val lastDate: String? = null,
    @SerialName("original_last_date") val originalLastDate: String? = null,
    val extended: Boolean = false,
    @SerialName("extension_history") val extensionHistory: List<Extension>? = null,
    @SerialName("notice_url") val noticeUrl: String = "",
    @SerialName("apply_url") val applyUrl: String? = null,
    @SerialName("page_url") val pageUrl: String? = null,
    val location: String? = null,
    @SerialName("how_to_apply") val howToApply: String? = null,
    val summary: String? = null,
    @SerialName("deputation_tenure") val deputationTenure: String? = null,
    @SerialName("eligible_cadre") val eligibleCadre: String? = null,
    @SerialName("first_seen") val firstSeen: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
    val status: String = "live",
    @SerialName("closed_at") val closedAt: String? = null,
) {
    val last: LocalDate? get() = lastDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val isDeputation: Boolean get() = kind == "deputation"
    val awaitingExtension: Boolean get() = status == "awaiting_extension_check"

    fun daysLeft(today: LocalDate = LocalDate.now()): Long? = last?.let { ChronoUnit.DAYS.between(today, it) }

    fun isNew(today: LocalDate = LocalDate.now()): Boolean = firstSeen?.let {
        runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it.take(10)), today) <= 3 }.getOrDefault(false)
    } ?: false
}

@Serializable
data class SourceInfo(
    val id: String,
    val name: String,
    val level: String,
    val state: String? = null,
    val home: String = "",
)

@Serializable
data class Feed(
    val schema: Int = 1,
    @SerialName("generated_at") val generatedAt: String = "",
    val jobs: List<Job> = emptyList(),
    val archive: List<Job> = emptyList(),
    val sources: List<SourceInfo> = emptyList(),
)

@Serializable
data class FeedMeta(
    val schema: Int = 1,
    @SerialName("generated_at") val generatedAt: String = "",
    val count: Int = 0,
    val hash: String = "",
)
