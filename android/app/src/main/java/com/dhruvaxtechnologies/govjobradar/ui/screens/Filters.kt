package com.dhruvaxtechnologies.govjobradar.ui.screens

import com.dhruvaxtechnologies.govjobradar.data.Eligibility
import com.dhruvaxtechnologies.govjobradar.data.Job
import com.dhruvaxtechnologies.govjobradar.data.Profile

/** Shared filtering for tabs and lists. */
object Filters {
    fun select(jobs: List<Job>, level: String?, sector: String?, state: String?, kind: String?): List<Job> = jobs.filter { j ->
        (kind == null || j.kind == kind) &&
            (kind != null || !j.isDeputation) &&                    // deputation lives in its own tab
            (level == null || j.level == level) &&
            (sector == null || j.sector == sector) &&
            (state == null || j.state == state)
    }

    fun apply(jobs: List<Job>, filter: String?, profile: Profile): List<Job> = when (filter) {
        "new" -> jobs.filter { it.isNew() }
        "closing" -> jobs.filter { (it.daysLeft() ?: 99) in 0..7 && !it.awaitingExtension }
        "eligible" -> jobs.filter { Eligibility.likelyEligible(it, profile) == true }
        "extended" -> jobs.filter { it.extended }
        else -> jobs
    }

    fun sortByDeadline(jobs: List<Job>): List<Job> =
        jobs.sortedWith(compareBy<Job> { it.awaitingExtension }.thenBy { it.lastDate ?: "9999" })
}
