package com.westly.nbms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.westly.nbms.core.design.NbmsTheme
import com.westly.nbms.core.design.ThemePreferenceStore
import com.westly.nbms.core.design.resolveDark
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeStore: ThemePreferenceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read the saved light / dark / system choice once before the first frame so the screen does not flash.
        val initialMode = runBlocking { themeStore.mode.first() }
        setContent {
            val mode by themeStore.mode.collectAsState(initial = initialMode)
            NbmsTheme(darkTheme = mode.resolveDark()) {
                NbmsRoot()
            }
        }
    }
}
