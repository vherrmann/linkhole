package com.vherrmann.linkhole.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vherrmann.linkhole.App
import com.vherrmann.linkhole.data.Item
import com.vherrmann.linkhole.data.Selection
import com.vherrmann.linkhole.data.State
import com.vherrmann.linkhole.data.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as App).repo

    private val unread: StateFlow<List<Item>> =
        repo.observeUnread().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Set while showing a random pick; null means "latest". */
    private val randomId = MutableStateFlow<Long?>(null)

    val current: StateFlow<Item?> = combine(unread, randomId) { list, rid ->
        list.firstOrNull { it.id == rid } ?: Selection.latest(list)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showingRandom: StateFlow<Boolean> = combine(current, randomId) { item, rid ->
        item != null && item.id == rid
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val unreadCount: StateFlow<Int> = unread.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val pending = repo.observePending().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val syncing = SyncStatus.running
    val syncError = SyncStatus.error

    fun showLatest() {
        randomId.value = null
    }

    /** Re-rolls on every call. */
    fun showRandom() {
        randomId.value = Selection.random(unread.value)?.id
    }

    fun mark(item: Item, state: String) {
        randomId.value = null
        viewModelScope.launch { repo.setState(item.id, state) }
    }

    fun undo(item: Item) {
        viewModelScope.launch { repo.setState(item.id, State.UNREAD) }
    }

    fun refresh() = repo.scheduleSync()
}
