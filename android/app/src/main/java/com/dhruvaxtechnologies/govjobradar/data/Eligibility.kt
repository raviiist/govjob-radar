package com.dhruvaxtechnologies.govjobradar.data

import java.time.LocalDate
import java.time.Period

/**
 * Rough eligibility from the user's profile. Always shown as "likely eligible" —
 * the official notification decides. Age relaxation uses the common central-government
 * norms (OBC +3, SC/ST +5, PwD +10, ex-servicemen +3); states may differ.
 */
object Eligibility {
    private val rank = mapOf(
        "any" to 0, "8th" to 1, "10th" to 2, "12th" to 3, "iti" to 3, "diploma" to 4,
        "graduate" to 5, "engineering" to 6, "medical" to 6, "postgraduate" to 7, "phd" to 8,
    )

    fun qualificationOk(job: Job, p: Profile): Boolean? {
        val need = job.minQualification
        val have = p.qualification ?: return null
        if (need == "unknown") return null
        if (need == "any") return true
        // Special streams: engineering / medical posts need that stream (or higher in it).
        if (need == "engineering") return have == "engineering" || (have in setOf("postgraduate", "phd"))
        if (need == "medical") return have == "medical"
        if (need == "iti") return have == "iti" || (rank[have] ?: 0) >= 4
        return (rank[have] ?: 0) >= (rank[need] ?: 99)
    }

    fun relaxation(p: Profile): Int {
        var r = when (p.category) { "OBC" -> 3; "SC", "ST" -> 5; else -> 0 }
        if (p.pwd) r += 10
        if (p.exServiceman) r += 3
        return r
    }

    fun ageOk(job: Job, p: Profile): Boolean? {
        val dob = p.dob ?: return null
        val asOn = job.ageAsOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: job.last ?: LocalDate.now()
        val age = Period.between(dob, asOn).years
        job.ageMin?.let { if (age < it) return false }
        job.ageMax?.let { return age <= it + relaxation(p) }
        return if (job.ageMin == null) null else true
    }

    fun payLevelOk(job: Job, p: Profile): Boolean? {
        val mine = p.payLevel ?: return null
        val lo = job.payLevelMin ?: return null
        val hi = job.payLevelMax ?: lo
        // Deputation posts are usually open to officers one or two levels below, or at, the post level.
        return mine in (lo - 2)..hi
    }

    /** null = not enough information to say. */
    fun likelyEligible(job: Job, p: Profile): Boolean? {
        if (job.level == "state" && p.homeState != null && job.state != null && job.state != p.homeState) {
            // Many state posts need domicile; don't hide them, just don't call them "eligible".
            return null
        }
        val checks = if (job.isDeputation) {
            if (!p.govtEmployee) return false
            listOf(payLevelOk(job, p))
        } else listOf(qualificationOk(job, p), ageOk(job, p))
        if (checks.any { it == false }) return false
        return if (checks.all { it == true }) true else null
    }
}
