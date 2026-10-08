package ir.sabou.kernel

/**
 * Every business rule violation has a stable machine code (for tests, logs and sync) and a
 * Persian message for the user. Technical exceptions are never shown to users (AUD-024).
 */
sealed class DomainError(val code: String, val userMessage: String) {
    class InvalidInput(val field: String, message: String) : DomainError("INVALID_INPUT:$field", message)
    class NotFound(val entity: String) : DomainError("NOT_FOUND:$entity", "مورد درخواستی پیدا نشد.")
    data object AuthenticationRequired : DomainError("AUTHENTICATION_REQUIRED", "ابتدا وارد حساب کاربری شوید.")
    class PermissionDenied(val permission: String) : DomainError("PERMISSION_DENIED:$permission", "شما مجوز انجام این عملیات را ندارید.")
    class ScopeDenied(val scope: String) : DomainError("SCOPE_DENIED:$scope", "دسترسی به داده این شعبه مجاز نیست.")
    class InvalidState(val entity: String, val state: String) : DomainError("INVALID_STATE:$entity:$state", "وضعیت فعلی اجازه این عملیات را نمی‌دهد.")
    class IdempotencyConflict(val commandId: String) : DomainError("IDEMPOTENCY_CONFLICT", "این فرمان قبلاً با اطلاعات متفاوت ثبت شده است.")
    class UnbalancedJournal(val debit: Long, val credit: Long) : DomainError("UNBALANCED_JOURNAL", "جمع بدهکار و بستانکار سند برابر نیست.")
    class PeriodClosed(val epochDay: Long) : DomainError("PERIOD_CLOSED", "دوره مالی این تاریخ بسته است.")
    class ControlAccount(val accountCode: String) : DomainError("CONTROL_ACCOUNT:$accountCode", "این حساب کنترلی است و فقط از ماژول مالک قابل ثبت است.")
    class OwnedByAnotherModule(val owner: String) : DomainError("OWNED_BY:$owner", "این سند متعلق به ماژول دیگری است و فقط از همان ماژول قابل اصلاح است.")
    class InsufficientFunds(val account: String, val available: Long, val requested: Long) :
        DomainError("INSUFFICIENT_FUNDS:$account", "موجودی حساب برای این عملیات کافی نیست.")
    class InsufficientStock(val item: String, val available: Long, val requested: Long) :
        DomainError("INSUFFICIENT_STOCK:$item", "موجودی کالا کافی نیست.")
    class ConcurrentModification(val entity: String) : DomainError("CONCURRENT_MODIFICATION:$entity", "اطلاعات هم‌زمان تغییر کرده است؛ دوباره تلاش کنید.")
    class IntegrityViolation(val detail: String) : DomainError("INTEGRITY:$detail", "یکپارچگی داده تأیید نشد.")

    override fun toString(): String = code
}

class DomainException(val error: DomainError) : RuntimeException(error.code)

/** Throws a [DomainException] with [error] when [condition] is false. */
inline fun ensure(condition: Boolean, error: () -> DomainError) {
    if (!condition) throw DomainException(error())
}
