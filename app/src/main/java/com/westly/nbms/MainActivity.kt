package com.westly.nbms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.westly.nbms.core.design.GalleryScreen
import com.westly.nbms.core.design.NbmsTheme
import com.westly.nbms.core.design.ThemeMode
import com.westly.nbms.core.design.ToastViewModel
import com.westly.nbms.core.design.resolveDark
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val toastViewModel: ToastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Phase 7 replaces this temporary gallery with the real app shell and stores the theme choice.
            var mode by rememberSaveable { mutableStateOf(ThemeMode.System) }
            NbmsTheme(darkTheme = mode.resolveDark()) {
                GalleryScreen(
                    themeMode = mode,
                    onThemeModeChange = { mode = it },
                    toast = toastViewModel.controller
                )
            }
        }
    }
}
