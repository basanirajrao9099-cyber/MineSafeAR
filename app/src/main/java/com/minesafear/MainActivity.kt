package com.minesafear

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.minesafear.localization.AppLocaleManager
import com.minesafear.ui.MineSafeArApp
import com.minesafear.ui.theme.MineSafeArTheme

class MainActivity : ComponentActivity() {

    /**
     * Applies the stored language before any resource is read.
     *
     * This is the manual locale wrapping Android 10–12 needs: the platform per-app
     * language API is 33+, and `AppCompatDelegate.setApplicationLocales` would only
     * cover us here if this were an `AppCompatActivity` with an AppCompat theme.
     * A no-op on 33+, where the system has already done it. See [AppLocaleManager].
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MineSafeArTheme {
                MineSafeArApp()
            }
        }
    }
}
