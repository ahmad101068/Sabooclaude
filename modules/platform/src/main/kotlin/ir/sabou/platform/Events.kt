package ir.sabou.platform

/**
 * Business events are appended in the same transaction as the change. Today they are the local
 * history; when a server is added they become the synchronization stream (ADR-0001).
 */
data class DomainEvent(
    val eventId: String,
    val commandId: String,
    val module: ModuleId,
    val type: String,
    val scope: String,
    val occurredAtEpochMillis: Long,
    val payload: Map<String, String>,
)

interface EventLog {
    fun append(event: DomainEvent)
}
