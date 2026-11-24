package com.vherrmann.linkhole.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vherrmann.linkhole.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class Settings(val serverUrl: String = "", val token: String = "") {
    val isConfigured get() = serverUrl.isNotBlank() && token.isNotBlank()
}

/** Process-wide sync status shown in the UI. */
object SyncStatus {
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
}

class Repository(private val context: Context) {
    val db = AppDatabase.create(context)
    private val dao = db.dao()
    val store = RoomLocalStore(db)

    private val urlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("token")

    val settings: Flow<Settings> = context.dataStore.data.map {
        Settings(it[urlKey].orEmpty(), it[tokenKey].orEmpty())
    }

    suspend fun currentSettings() = settings.first()

    suspend fun saveSettings(s: Settings) {
        context.dataStore.edit {
            it[urlKey] = s.serverUrl.trim()
            it[tokenKey] = s.token.trim()
        }
        scheduleSync()
    }

    fun observeUnread() = dao.observeUnread()
    fun observe(state: String) = dao.observe(state)
    fun observePending() = dao.observeOpCount()

    /** Queue [url] for upload. Returns false if it is already known locally. */
    suspend fun addUrl(url: String, note: String? = null): Boolean {
        val added = db.withTransaction {
            if (dao.getByUrl(url) != null) return@withTransaction false
            val id = minOf(dao.minId() ?: 0L, 0L) - 1
            val now = System.currentTimeMillis()
            dao.upsert(Item(id = id, url = url, note = note?.ifBlank { null }, createdAt = now, updatedAt = now))
            dao.insertOp(PendingOp(type = OpType.ADD, itemId = id, url = url, note = note?.ifBlank { null }))
            true
        }
        if (added) scheduleSync()
        return added
    }

    suspend fun setState(id: Long, state: String) {
        db.withTransaction {
            dao.setState(id, state, System.currentTimeMillis())
            dao.insertOp(PendingOp(type = OpType.SET_STATE, itemId = id, state = state))
        }
        scheduleSync()
    }

    fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
