package org.urbackup.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    // bumped in onResume so permissions and folder contents are read again after returning to the app
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                // the first start goes straight to the settings
                var showSettings by rememberSaveable { mutableStateOf(!AppConfig.load(this).isComplete) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    HomeScreen(resumeCount = resumeCount, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
