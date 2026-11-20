package com.cleanroom.stadiumdsp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cleanroom.stadiumdsp.audio.OboeSessionController

/**
 * Single-Activity adaptive host (Milestone 5 bootstrap; upgraded to full 5-posture Compose UI in Milestone 6).
 * Preserves 0-ms audio continuity across rotation by NEVER calling stopAll() when isChangingConfigurations == true.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Hx Mobile — Stadium DSP Engine Ready",
                    color = Color.White,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // CRITICAL: Never interrupt Oboe audio stream during screen rotation or foldable posture changes!
        if (!isChangingConfigurations && isFinishing) {
            OboeSessionController.stopAll(applicationContext)
        }
    }
}
