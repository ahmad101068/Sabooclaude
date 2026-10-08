package ir.sabou.app

import android.app.Application
import ir.sabou.app.data.AppContainer

class SabouApplication : Application() {
    /** One container per process; it owns the database and the single SabouCore. */
    val container: AppContainer by lazy { AppContainer(this) }
    val ui = ir.sabou.app.ui.UiState()
}
