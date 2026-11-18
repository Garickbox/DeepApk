package com.garickbox.deepapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val DeepSeekBlue = Color(0xFF4D6BFE)

private val DeepDarkColors = darkColorScheme(
    primary = DeepSeekBlue,
    onPrimary = Color.White,
    background = Color(0xFF111215),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF181A1F),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF23262E),
    onSurfaceVariant = Color(0xFF9EA6B5),
    primaryContainer = Color(0xFF232B42),
    onPrimaryContainer = Color(0xFFE8EEFF)
)

private val DeepLightColors = lightColorScheme(
    primary = DeepSeekBlue,
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFF0F2F6),
    onSurfaceVariant = Color(0xFF6B7280),
    primaryContainer = Color(0xFFEFF4FF),
    onPrimaryContainer = Color(0xFF1A1C1E)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(SettingsStore.load(this).themeMode) }
            val sysDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> sysDark
            }
            MaterialTheme(colorScheme = if (isDark) DeepDarkColors else DeepLightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var screen by remember { mutableStateOf("chat") }
                    BackHandler(enabled = screen != "chat") {
                        screen = "chat"
                    }
                    when (screen) {
                        "chat" -> ChatScreen(
                            isDark = isDark,
                            onOpenSettings = { screen = "settings" }
                        )
                        "settings" -> SettingsScreen(
                            isDark = isDark,
                            onThemeChange = { themeMode = it },
                            onBack = { screen = "chat" }
                        )
                    }
                }
            }
        }
    }
}
