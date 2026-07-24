package com.scamshield.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scamshield.app.ui.ScamShieldApp
import com.scamshield.app.ui.theme.ScamShieldTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's single activity.
 *
 * `launchMode="singleTask"` plus [onNewIntent] means sharing a second message while
 * ScamShield is already open replaces the pending text rather than stacking activities —
 * a 60-year-old user should never end up four back-presses deep (G6).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sharedText = extractSharedText(intent)

        setContent {
            ScamShieldTheme {
                ScamShieldApp(
                    sharedText = sharedText,
                    onSharedTextConsumed = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedText(intent)?.let { sharedText = it }
    }

    /**
     * Pulls message text out of a share-sheet or text-selection intent.
     *
     * Returns null for a plain launcher start. Nothing here is logged — constraint C1 makes
     * message text unloggable anywhere in the app, including during ingest.
     */
    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)

            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
