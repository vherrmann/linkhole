package com.vherrmann.thenextthing.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vherrmann.thenextthing.App
import com.vherrmann.thenextthing.data.SyncStatus

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as App).repo
        val settings = repo.currentSettings()
        val api = if (settings.isConfigured) ApiFactory.create(settings.serverUrl, settings.token) else null
        if (api == null) {
            SyncStatus.error.value = "Server not configured"
            return Result.success()
        }
        SyncStatus.running.value = true
        try {
            return when (SyncEngine(api, repo.store).sync()) {
                SyncResult.OK -> {
                    SyncStatus.error.value = null
                    Result.success()
                }
                SyncResult.RETRY -> {
                    SyncStatus.error.value = "Offline or server unavailable; will retry"
                    Result.retry()
                }
                SyncResult.UNAUTHORIZED -> {
                    SyncStatus.error.value = "Unauthorized: check the token"
                    Result.failure()
                }
            }
        } finally {
            SyncStatus.running.value = false
        }
    }
}
