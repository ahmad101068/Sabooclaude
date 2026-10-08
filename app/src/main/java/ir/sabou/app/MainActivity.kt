package ir.sabou.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ir.sabou.app.ui.AppRoot
import ir.sabou.app.ui.theme.SabouTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Financial data: keep it out of screenshots and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val container = (application as SabouApplication).container
        setContent {
            SabouTheme {
                AppRoot(container, (application as SabouApplication).ui)
            }
        }
    }
}
