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
        val app = application as SabouApplication
        val container = app.container
        lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // Going to the background: the system may kill the process, so keep the unfinished form
                // (taken on the main thread, encrypted and written off it).
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // Nobody signed in (e.g. the PIN screen after a restart): keep the draft waiting for sign-in.
                    runCatching { app.ui.draftSnapshot(System.currentTimeMillis()) }.getOrNull()?.let(container::saveDraftsLater)
                }
                // Back in the foreground with the process alive: nothing to restore later.
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (app.ui.session != null) container.clearDraftsLater()
                    container.verifyInBackgroundIfDue()
                }
                else -> Unit
            }
        })
        setContent {
            SabouTheme {
                AppRoot(container, (application as SabouApplication).ui)
            }
        }
    }
}
