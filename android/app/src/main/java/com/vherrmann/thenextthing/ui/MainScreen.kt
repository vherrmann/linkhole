package com.vherrmann.thenextthing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vherrmann.thenextthing.data.Item
import com.vherrmann.thenextthing.data.State
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onList: () -> Unit, onSettings: () -> Unit) {
    val item by vm.current.collectAsState()
    val showingRandom by vm.showingRandom.collectAsState()
    val count by vm.unreadCount.collectAsState()
    val pending by vm.pending.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val error by vm.syncError.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun mark(it: Item, state: String, label: String) {
        vm.mark(it, state)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(label, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                == SnackbarResult.ActionPerformed
            ) vm.undo(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$count unread") },
                actions = {
                    TextButton(onClick = onList) { Text("List") }
                    TextButton(onClick = onSettings) { Text("Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                val status = listOfNotNull(
                    error,
                    if (pending > 0) "$pending change(s) waiting to sync" else null,
                ).joinToString(" · ")
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                val current = item
                if (current == null) {
                    Text("Nothing unread. Pull down to refresh.", style = MaterialTheme.typography.titleMedium)
                } else {
                    ItemCard(current, if (showingRandom) "Random" else "Latest")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(onClick = vm::showLatest, enabled = showingRandom, modifier = Modifier.weight(1f)) {
                            Text("Latest")
                        }
                        FilledTonalButton(onClick = vm::showRandom, modifier = Modifier.weight(1f)) { Text("Random") }
                    }
                    Button(onClick = { openUrl(context, current.url) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { mark(current, State.READ, "Marked read") }, modifier = Modifier.weight(1f)) {
                            Text("Read")
                        }
                        OutlinedButton(
                            onClick = { mark(current, State.UNINTERESTING, "Marked uninteresting") },
                            modifier = Modifier.weight(1f),
                        ) { Text("Uninteresting", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: Item, badge: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(badge.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(item.displayTitle(), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${item.host()} · ${age(item.createdAt)}", style = MaterialTheme.typography.bodyMedium)
            }
            item.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
