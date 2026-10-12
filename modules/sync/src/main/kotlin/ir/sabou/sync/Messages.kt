package ir.sabou.sync

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.persistence.SyncLog
import ir.sabou.platform.Command
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId

/** What the two phones say to each other inside the encrypted channel (ADR-0042). */
sealed class Message : java.io.Serializable {
    // Pairing
    data object PairConfirm : Message()
    data class PairAccepted(val hubName: String) : Message()

    // Every session starts with the app version: both phones must run the same build.
    data class Hello(val appVersion: String) : Message()
    data class HelloOk(val epoch: String) : Message()

    // Sign-in on the second phone, checked by the main phone. [token] stands for the signed-in person.
    class Login(val username: String, val pin: CharArray) : Message()
    data class LoginOk(val token: String, val userId: GlobalId, val cursor: Long) : Message()
    class ConfirmPin(val token: String, val pin: CharArray) : Message()
    data class Logout(val token: String) : Message()

    // A command, run on the main phone; [cursor]: the log position that includes its effects.
    data class Execute(val token: String, val module: ModuleId, val command: Command) : Message()
    data class Executed(val outcome: CommandOutcome, val cursor: Long) : Message()

    // Replication: changes after [cursor], waiting up to [waitMillis] for some to happen.
    data class Pull(val cursor: Long, val waitMillis: Long) : Message()
    data class Changes(val batch: SyncLog.Batch) : Message()

    // A full copy: the header, then raw chunks, then an empty chunk.
    data object CopyRequest : Message()
    data class CopyHeader(val epoch: String, val cursor: Long, val key: String, val size: Long) : Message()

    data object Ok : Message()
    data class Failure(val error: DomainError) : Message()
}

/** Errors of the link itself, shown in Persian by the app's message table. */
object SyncErrors {
    val HUB_UNREACHABLE = DomainError.InvalidState("DEVICE", "HUB_UNREACHABLE")
    val VERSION = DomainError.InvalidState("DEVICE", "VERSION")
    val NOT_PAIRED = DomainError.InvalidState("DEVICE", "NOT_PAIRED")
    val HUB_NOT_READY = DomainError.InvalidState("DEVICE", "HUB_NOT_READY")
}
