package com.vherrmann.thenextthing.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import android.os.Build

private enum class Screen { MAIN, LIST, SETTINGS }

@Composable
fun AppRoot() {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        val vm: MainViewModel = viewModel()
        var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
        BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
        when (screen) {
            Screen.MAIN -> MainScreen(vm, onList = { screen = Screen.LIST }, onSettings = { screen = Screen.SETTINGS })
            Screen.LIST -> ListScreen(vm, onBack = { screen = Screen.MAIN })
            Screen.SETTINGS -> SettingsScreen(vm, onBack = { screen = Screen.MAIN })
        }
    }
}
