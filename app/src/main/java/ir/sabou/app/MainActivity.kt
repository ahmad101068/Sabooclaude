package ir.sabou.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ir.sabou.app.ui.AppRoot
import ir.sabou.app.ui.theme.SabouTheme

class MainActivity : ComponentActivity() {
    private var backgroundSince: Long? = null

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
                    backgroundSince = System.currentTimeMillis()
                    // Nobody signed in (e.g. the PIN screen after a restart): keep the draft waiting for sign-in.
                    runCatching { app.ui.draftSnapshot(System.currentTimeMillis()) }.getOrNull()?.let(container::saveDraftsLater)
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val away = backgroundSince?.let { System.currentTimeMillis() - it } ?: 0L
                    backgroundSince = null
                    // Left in the background for a while on a shared phone: sign out (SABOU-AUDIT-011). The form taken at
                    // ON_STOP stays saved and comes back when the same person signs in again.
                    if (app.ui.session != null && away > SIGN_OUT_AFTER_BACKGROUND_MILLIS) app.ui.signOut()
                    // Back in the foreground with the session alive: nothing to restore later.
                    else if (app.ui.session != null) container.clearDraftsLater()
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

    private companion object {
        const val SIGN_OUT_AFTER_BACKGROUND_MILLIS = 5 * 60_000L
    }
}
