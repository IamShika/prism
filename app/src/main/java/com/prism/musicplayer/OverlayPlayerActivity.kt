package com.prism.musicplayer

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prism.musicplayer.ui.OverlayPlayer
import com.prism.musicplayer.ui.PrismTheme

/**
 * The small player that appears over another app (for example a file manager) when you open
 * a music file with Prism. The file plays right there; the other app stays visible behind it.
 * Tap outside the card to close it. The music keeps playing in the background.
 */
class OverlayPlayerActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        // Only start the song the first time, not again when the phone is rotated.
        if (savedInstanceState == null) vm.playExternal(uri)

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            PrismTheme(accent = Color(state.accent)) {
                OverlayPlayer(
                    state = state,
                    vm = vm,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}
