package org.urbackup.client.android

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Periodically starts a backup session. Whether a backup actually runs is up to the server. */
class SessionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!AppConfig.load(applicationContext).isComplete) return Result.success()
        try {
            BackupService.start(applicationContext, BackupService.ACTION_SESSION)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Android 12+ only allows this from the background if the app is exempt from battery optimization
            Log.w("UrBackup", "session start not allowed", e)
            BackupState.setLastResult(
                applicationContext,
                "A scheduled backup could not start. Allow UrBackup to run in the background (battery settings).",
                ok = false,
            )
        }
        return Result.success()
    }
}

object Scheduling {
    private const val WORK_NAME = "session"

    /** When WorkManager plans the next session (ms), null if nothing is scheduled. */
    fun nextRun(context: Context): Flow<Long?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            infos.firstOrNull { !it.state.isFinished }?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE }
        }

    fun apply(context: Context) {
        val config = AppConfig.load(context)
        val wm = WorkManager.getInstance(context)
        if (!config.isComplete) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(config.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<SessionWorker>(config.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
