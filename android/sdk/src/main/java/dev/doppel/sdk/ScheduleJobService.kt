package dev.doppel.sdk

import android.app.job.JobParameters
import android.app.job.JobService

/** System-approved timing wakeup. It never starts a foreground service by itself. */
class ScheduleJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        return try { ScheduleManager.get(this).tickFromSystemJob { jobFinished(params, false) }; true }
        catch (_: Exception) { false }
    }
    override fun onStopJob(params: JobParameters): Boolean = false
}
