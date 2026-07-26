package com.mmwtl.atlascodecfix

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutoApplyJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = scope.launch {
            val shouldRetry = try {
                performAutoApply()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Auto apply job failed", t)
                retryOrStop(t.message ?: t.javaClass.simpleName)
            }
            jobFinished(params, shouldRetry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        val prefs = currentApp().prefs
        if (
            (!prefs.autoApplyCodecFix && !prefs.autoApplyMediaFix) ||
            !prefs.adbEnabled
        ) {
            prefs.autoApplyRetryCount = 0
            return false
        }
        val nextAttempt = AutoApplyRetryPolicy.nextRetryCount(prefs.autoApplyRetryCount)
        return if (nextAttempt == null) {
            prefs.autoApplyRetryCount = 0
            false
        } else {
            prefs.autoApplyRetryCount = nextAttempt
            true
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun performAutoApply(): Boolean {
        val app = currentApp()
        val prefs = app.prefs
        if (
            (!prefs.autoApplyCodecFix && !prefs.autoApplyMediaFix) ||
            !prefs.adbEnabled
        ) {
            prefs.autoApplyRetryCount = 0
            return false
        }

        if (!app.adbClient.connect()) {
            return retryOrStop(getString(R.string.auto_apply_connect_failed))
        }

        val targetVariant = if (prefs.advancedFixEnabled) {
            prefs.selectedVariant
        } else {
            HevcCodecFixVariant.MIN
        }
        val result = app.codecFixRepository.runAutoApply(
            AutoApplyRequest(
                applyCodecFix = prefs.autoApplyCodecFix,
                applyMediaFix = prefs.autoApplyMediaFix,
                variant = targetVariant,
                skipCompatibilityCheck = prefs.skipCompatibilityCheck
            )
        )
        if (result.success) {
            prefs.autoApplyRetryCount = 0
            Log.i(
                TAG,
                "Auto apply codec=${prefs.autoApplyCodecFix}:${targetVariant.argument}, " +
                    "media=${prefs.autoApplyMediaFix}: success"
            )
            return false
        }

        val message = result.output
            .trim()
            .takeLast(ERROR_TEXT_LIMIT)
            .ifBlank { getString(R.string.auto_apply_failed) }
        return if (result.retryable) {
            retryOrStop(message)
        } else {
            prefs.autoApplyRetryCount = 0
            Log.w(TAG, message)
            app.errorNotifier.notify(getString(R.string.app_name), message)
            false
        }
    }

    private fun retryOrStop(message: String): Boolean {
        val app = currentApp()
        val nextAttempt = AutoApplyRetryPolicy.nextRetryCount(app.prefs.autoApplyRetryCount)
        if (nextAttempt == null) {
            app.prefs.autoApplyRetryCount = 0
            val finalMessage = getString(
                R.string.auto_apply_stopped,
                AutoApplyRetryPolicy.MAX_ATTEMPTS,
                message
            )
            Log.e(TAG, finalMessage)
            app.errorNotifier.notify(getString(R.string.app_name), finalMessage)
            return false
        }

        app.prefs.autoApplyRetryCount = nextAttempt
        Log.w(TAG, "Auto apply retry $nextAttempt/${AutoApplyRetryPolicy.MAX_ATTEMPTS}: $message")
        return true
    }

    private fun currentApp(): CodecFixApp = application as CodecFixApp

    private companion object {
        private const val TAG = "AtlasCodecFix"
        private const val ERROR_TEXT_LIMIT = 220
    }
}
