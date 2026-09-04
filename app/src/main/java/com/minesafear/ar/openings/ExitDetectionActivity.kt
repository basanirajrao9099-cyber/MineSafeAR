package com.minesafear.ar.openings

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.minesafear.localization.AppLocaleManager
import com.minesafear.ui.theme.MineSafeArTheme

/**
 * Host for [ExitDetectionScreen]. Separate activity rather than a nav destination for the same
 * reason as ARTestActivity: the bottom bar is already five items wide, and a full-screen viewfinder
 * fights the nav scaffold. configChanges keeps the CameraX binding and every world-anchored track
 * alive across rotation instead of tearing the analyser down mid-scan.
 */
class ExitDetectionActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MineSafeArTheme {
                ExitDetectionScreen(onClose = { finish() }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
