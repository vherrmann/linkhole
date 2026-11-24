package com.vherrmann.linkhole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vherrmann.linkhole.data.Item
import com.vherrmann.linkhole.data.State
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: MainViewModel, onBack: () -> Unit) {
    var filter by remember { mutableStateOf(State.UNREAD) }
    val items by remember(filter) { vm.repo.observe(filter) }.collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun mark(item: Item, state: String, label: String) {
        val previous = item.state
        vm.mark(item, state)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Short) ==
                SnackbarResult.ActionPerformed
            ) vm.repo.setState(item.id, previous)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(State.UNREAD, State.READ, State.UNINTERESTING, State.ALL).forEach {
                    FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it) })
                }
            }
            Text(
                "Swipe right: read · swipe left: uninteresting",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    val dismiss = rememberSwipeToDismissBoxState()
                    LaunchedEffect(dismiss.currentValue) {
                        when (dismiss.currentValue) {
                            SwipeToDismissBoxValue.StartToEnd -> mark(item, State.READ, "Marked read")
                            SwipeToDismissBoxValue.EndToStart -> mark(item, State.UNINTERESTING, "Marked uninteresting")
                            SwipeToDismissBoxValue.Settled -> {}
                        }
                        // Items that stay in the list (other filters) must snap back.
                        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) dismiss.reset()
                    }
                    SwipeToDismissBox(
                        state = dismiss,
                        backgroundContent = {
                            val read = dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                            Box(
                                Modifier.fillMaxSize().background(
                                    if (read) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.tertiaryContainer
                                ).padding(horizontal = 16.dp),
                                contentAlignment = if (read) Alignment.CenterStart else Alignment.CenterEnd,
                            ) { Text(if (read) "Read" else "Uninteresting") }
                        },
                    ) {
                        Column(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                .clickable { openUrl(context, item.url) }.padding(16.dp),
                        ) {
                            Text(item.displayTitle(), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${item.host()} · ${age(item.createdAt)}" +
                                    if (item.state != State.UNREAD) " · ${item.state}" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
