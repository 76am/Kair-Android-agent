package dev.kair.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.kair.app.app.KairApplication
import dev.kair.app.ui.KairNavHost
import dev.kair.app.ui.theme.KairTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KairApplication).container
        setContent {
            KairTheme {
                KairNavHost(container)
            }
        }
    }
}
