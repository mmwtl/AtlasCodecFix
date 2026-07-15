package com.mmwtl.atlascodecfix

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

object AutoApplyScheduler {
    fun sync(
        context: Context,
        resetRetries: Boolean = false,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS
    ): Boolean {
        val app = context.applicationContext as CodecFixApp
        return if (app.prefs.autoApply && app.prefs.adbEnabled) {
            schedule(context, resetRetries, initialDelayMs)
        } else {
            cancel(context)
            true
        }
    }

    fun schedule(
        context: Context,
        resetRetries: Boolean = false,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS
    ): Boolean {
        val app = context.applicationContext as CodecFixApp
        if (resetRetries) app.prefs.autoApplyRetryCount = 0

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, AutoApplyJobService::class.java)
        )
            .setMinimumLatency(initialDelayMs.coerceAtLeast(0L))
            .setBackoffCriteria(RETRY_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()

        val scheduled = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        if (!scheduled) Log.e(TAG, "Unable to schedule auto apply job")
        return scheduled
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        val app = context.applicationContext as CodecFixApp
        app.prefs.autoApplyRetryCount = 0
    }

    private const val TAG = "AtlasCodecFix"
    private const val JOB_ID = 0x41544346
    private const val DEFAULT_INITIAL_DELAY_MS = 12_000L
    private const val RETRY_BACKOFF_MS = 30_000L
}
