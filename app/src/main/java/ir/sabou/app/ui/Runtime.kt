package ir.sabou.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ir.sabou.core.Messages
import ir.sabou.core.SabouCore
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.Actor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Everything a signed-in screen needs. Provided once by the app shell. */
@Stable
class AppSession(
    val core: SabouCore,
    val actor: Actor,
    branch: BranchId?,
    private val onSignedOut: () -> Unit,
) {
    var branchId by mutableStateOf(branch)
    /** Bumped after every successful write so every loaded view refreshes. */
    var version by mutableIntStateOf(0)
        private set

    val branch: Scope.Branch? get() = branchId?.let { Scope.Branch(it) }
    val today: BusinessDate get() = BusinessDate(LocalDate.now().toEpochDay())

    fun changed() { version++ }
    fun signedOut() = onSignedOut()
}

val LocalSession = compositionLocalOf<AppSession> { error("no session") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

fun <T> Load<T>.orNull(): T? = (this as? Load.Done<T>)?.value

/** Loads on a background thread; reloads when [keys] or the session version change. */
@Composable
fun <T> load(session: AppSession, vararg keys: Any?, block: SabouCore.() -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, session.version, *keys) {
        value = try {
            Load.Done(withContext(Dispatchers.IO) { session.core.block() })
        } catch (e: DomainException) {
            if (e.error == DomainError.AuthenticationRequired) session.signedOut()
            Load.Failed(Messages.of(e))
        } catch (e: Exception) {
            Load.Failed(Messages.of(e))
        }
    }

/** Runs one write: busy flag, Persian error text, refresh on success, sign-out when the session expired. */
@Stable
class Action internal constructor(private val scope: CoroutineScope, private val session: AppSession) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun <T> run(block: SabouCore.() -> T, onSuccess: (T) -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { session.core.block() }
                session.changed()
                onSuccess(result)
            } catch (e: DomainException) {
                if (e.error == DomainError.AuthenticationRequired) session.signedOut()
                error = Messages.of(e)
            } catch (e: Exception) {
                error = Messages.of(e)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberAction(): Action {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    return remember(session) { Action(scope, session) }
}

/** A fresh command id per form instance, so a double tap or a retry is recognised as the same command. */
@Composable
fun rememberCommandId(vararg keys: Any?): MutableState<GlobalId> = remember(*keys) { mutableStateOf(GlobalId.new()) }

@Composable
fun OnChange(key: Any?, block: () -> Unit) {
    LaunchedEffect(key) { block() }
}
