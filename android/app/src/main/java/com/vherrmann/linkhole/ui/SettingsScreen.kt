package com.vherrmann.linkhole.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vherrmann.linkhole.data.Settings
import com.vherrmann.linkhole.sync.ApiFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val s = vm.repo.currentSettings()
        url = s.serverUrl
        token = s.token
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Server URL") },
                placeholder = { Text("https://things.example.org") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, label = { Text("Token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    result = "Testing…"
                    scope.launch { result = testConnection(url, token) }
                }) { Text("Test connection") }
                Button(onClick = {
                    scope.launch {
                        vm.repo.saveSettings(Settings(url, token))
                        result = "Saved"
                    }
                }) { Text("Save") }
            }
            result?.let { Text(it) }
        }
    }
}

private suspend fun testConnection(url: String, token: String): String {
    val api = ApiFactory.create(url, token) ?: return "Invalid server URL (needs http:// or https://)"
    return try {
        val r = api.latest()
        r.body()?.close()
        when {
            r.isSuccessful -> "Connection OK"
            r.code() == 401 -> "Unauthorized: wrong token"
            else -> "Server answered HTTP ${r.code()}"
        }
    } catch (e: Exception) {
        "Failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
