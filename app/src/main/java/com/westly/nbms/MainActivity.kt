package com.westly.nbms

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.westly.nbms.core.design.NbmsTheme
import com.westly.nbms.core.design.ThemeMode
import com.westly.nbms.core.design.ThemePreferenceStore
import com.westly.nbms.core.design.resolveDark
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** A FragmentActivity (still a ComponentActivity) because the system biometric prompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var themeStore: ThemePreferenceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read the saved light / dark / system choice once before the first frame so the screen does not flash.
        // A damaged settings file must never keep the app from opening: give up after 1.5 seconds and follow the phone.
        val initialMode = runBlocking { withTimeoutOrNull(1_500L) { themeStore.mode.first() } } ?: ThemeMode.System
        setContent {
            val mode by themeStore.mode.collectAsState(initial = initialMode)
            NbmsTheme(darkTheme = mode.resolveDark()) {
                NbmsRoot()
            }
        }
    }
}
