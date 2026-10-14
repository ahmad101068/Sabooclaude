package ir.sabou.platform

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Branch(val id: BranchId, val name: String, val isActive: Boolean = true)

data class User(
    val id: GlobalId,
    val username: String,
    val displayName: String,
    val role: Role,
    val branchGrants: Set<BranchId>,
    val pinHash: String,
    val failedAttempts: Int,
    /** The current temporary lock after wrong PINs, or null. */
    val lock: PinLock?,
    val isActive: Boolean,
    /** Locked until the owner resets the PIN (too many wrong PINs for a non-owner). */
    val ownerLocked: Boolean = false,
    /** Hash of the owner's one-time recovery code (ADR-0023), or null when none is issued. */
    val recoveryHash: String? = null,
) {
    fun toActor() = Actor(id, displayName, role, branchGrants)
}

/**
 * A temporary login lock that changing the device clock cannot shorten. Within the same boot it is measured on
 * the monotonic clock ([untilElapsed]); across a reboot on the wall clock ([untilWall]); and a wall clock set back
 * before the lock began ([startWall]) keeps it locked.
 */
data class PinLock(val startWall: Long, val untilWall: Long, val bootId: String, val untilElapsed: Long) {
    fun isActive(wall: Long, time: DeviceTime): Boolean = when {
        wall < startWall -> true
        bootId == time.bootId() -> time.elapsedMillis() < untilElapsed
        else -> wall < untilWall
    }

    /** How long until the lock ends, as the same clocks as [isActive] see it. */
    fun remainingSeconds(wall: Long, time: DeviceTime): Long {
        val millis = if (bootId == time.bootId()) untilElapsed - time.elapsedMillis() else untilWall - maxOf(wall, startWall)
        return (millis.coerceAtLeast(0) + 999) / 1000
    }
}

/** The device's monotonic time since boot, and an id of the current boot. */
interface DeviceTime {
    fun bootId(): String
    fun elapsedMillis(): Long

    companion object {
        /** JVM fallback: the process stands in for a boot. Android supplies the real boot count and uptime. */
        val PROCESS: DeviceTime = object : DeviceTime {
            private val id = java.util.UUID.randomUUID().toString()
            override fun bootId() = id
            override fun elapsedMillis() = System.nanoTime() / 1_000_000
        }
    }
}

interface UserStore {
    fun byId(id: GlobalId): User?
    fun byUsername(username: String): User?
    fun all(): List<User>
    fun save(user: User)
}

interface BranchStore {
    fun byId(id: BranchId): Branch?
    fun all(): List<Branch>
    fun save(branch: Branch)
}

object PinHasher {
    private const val ITERATIONS = 210_000

    fun hash(pin: CharArray): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return "pbkdf2-sha256$" + ITERATIONS + "$" + b64(salt) + "$" + b64(derive(pin, salt, ITERATIONS))
    }

    fun verify(pin: CharArray, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        return MessageDigest.isEqual(expected, derive(pin, salt, iterations))
    }

    /**
     * A deterministic digest of [pin] for command fingerprints (audit F10): a retried command with a different PIN
     * must not pass as the same command. Salted by [context] (the command id) and as slow as a stored PIN hash, so
     * the kept idempotency record is no easier to guess from than the PIN hash itself.
     */
    fun fingerprint(pin: CharArray, context: String): String =
        b64(derive(pin, MessageDigest.getInstance("SHA-256").digest(("sabou-fp|" + context).toByteArray(Charsets.UTF_8)), ITERATIONS))

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/** Sign-in through the main phone, for a second phone (ADR-0042). Both throw [DomainException] when refused. */
interface RemoteIdentity {
    /** Checks the PIN on the main phone and returns the user's id. */
    fun login(username: String, pin: CharArray): GlobalId
    fun confirmPin(userId: GlobalId, pin: CharArray)
}

/** The in-process session. A fresh process always starts logged out. */
class Session(private val users: UserStore, private val clock: Clock, private val idleTimeoutMillis: Long = 30 * 60_000L) : SessionPort {
    @Volatile private var userId: GlobalId? = null
    @Volatile private var lastActivity: Long = 0

    /** A read-only stand-in for this thread (see [readAs]); the signed-in session is untouched. */
    private val reading = ThreadLocal<Actor?>()

    override fun readOnly(): Boolean = reading.get() != null

    /** The person a command from the second phone runs for, on this thread only (ADR-0042). */
    private val acting = ThreadLocal<Actor?>()

    /**
     * Runs [block] on this thread for [actor], who signed in on the second phone and was verified by this phone.
     * Commands inside are checked and audited as that person; the person signed in here is untouched.
     */
    fun <T> actAs(actor: Actor, block: () -> T): T {
        val previous = acting.get()
        acting.set(actor)
        try { return block() } finally { acting.set(previous) }
    }

    /**
     * Runs [block] on this thread as [actor], for reads only: every read model applies that person's permissions
     * and branches, while any command inside is refused. Used for alerts computed in the background.
     */
    fun <T> readAs(actor: Actor, block: () -> T): T {
        val previous = reading.get()
        reading.set(actor)
        try { return block() } finally { reading.set(previous) }
    }

    @Synchronized
    override fun currentActor(): Actor? {
        reading.get()?.let { return it }
        acting.get()?.let { return it }
        val id = userId ?: return null
        val now = clock.nowEpochMillis()
        if (now - lastActivity > idleTimeoutMillis) { userId = null; return null }
        val user = users.byId(id)
        if (user == null || !user.isActive) { userId = null; return null }
        lastActivity = now
        return user.toActor()
    }

    @Synchronized internal fun start(id: GlobalId) { userId = id; lastActivity = clock.nowEpochMillis() }
    @Synchronized fun end() { userId = null }
}

/**
 * Users, branches and login. The first user of a fresh installation must be the owner; afterwards
 * only the owner manages users. Wrong PINs lock the account with growing delays.
 */
class IdentityService(
    private val users: UserStore,
    private val branches: BranchStore,
    private val session: Session,
    private val unitOfWork: UnitOfWork,
    private val audit: AuditStore,
    private val clock: Clock,
    private val deviceTime: DeviceTime = DeviceTime.PROCESS,
    private val epochProvider: () -> String,
) {
    private val trail = AuditTrail(audit)

    /**
     * Set on a second phone (ADR-0042). PINs are checked by the main phone, which also counts wrong attempts and
     * audits; this phone only starts the session. Managing users and PINs is done on the main phone.
     */
    @Volatile var remote: RemoteIdentity? = null

    private fun hubOnly() = ensure(remote == null) { DomainError.InvalidState("DEVICE", "HUB_ONLY") }

    fun needsBootstrap(): Boolean = users.all().isEmpty()

    fun bootstrapOwner(username: String, displayName: String, pin: CharArray): GlobalId = unitOfWork.transaction {
        hubOnly()
        ensure(users.all().isEmpty()) { DomainError.InvalidState("USERS", "ALREADY_BOOTSTRAPPED") }
        val user = newUser(username, displayName, Role.OWNER, emptySet(), pin)
        users.save(user)
        session.start(user.id)
        trail.append(AuditDraft("USER_BOOTSTRAP_OWNER", "USER", user.id.value, user.username), user.toActor(), ModuleId.PLATFORM, "ORG", "bootstrap", clock.nowEpochMillis(), epochProvider())
        user.id
    }

    fun createBranch(name: String): BranchId = asOwner { actor ->
        hubOnly()
        val trimmed = name.trim()
        ensure(trimmed.length in 2..80 && branches.all().none { it.name == trimmed }) { DomainError.InvalidInput("name", "نام شعبه معتبر یا تکراری است.") }
        val branch = Branch(BranchId(GlobalId.new()), trimmed)
        branches.save(branch)
        trail.append(AuditDraft("BRANCH_CREATE", "BRANCH", branch.id.value.value, trimmed), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
        branch.id
    }

    fun renameBranch(id: BranchId, name: String) = asOwner { actor ->
        hubOnly()
        val branch = branches.byId(id) ?: throw DomainException(DomainError.NotFound("BRANCH"))
        val trimmed = name.trim()
        ensure(trimmed.length in 2..80 && branches.all().none { it.id != id && it.name == trimmed }) { DomainError.InvalidInput("name", "نام شعبه معتبر یا تکراری است.") }
        branches.save(branch.copy(name = trimmed))
        trail.append(AuditDraft("BRANCH_RENAME", "BRANCH", id.value.value, "${branch.name}->$trimmed"), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
    }

    fun createUser(username: String, displayName: String, role: Role, grants: Set<BranchId>, pin: CharArray): GlobalId = asOwner { actor ->
        hubOnly()
        ensure(role != Role.OWNER || grants.isEmpty()) { DomainError.InvalidInput("grants", "مالک به همه شعب دسترسی دارد.") }
        grants.forEach { ensure(branches.byId(it)?.isActive == true) { DomainError.NotFound("BRANCH") } }
        val user = newUser(username, displayName, role, grants, pin)
        users.save(user)
        trail.append(AuditDraft("USER_CREATE", "USER", user.id.value, "${user.username}:$role"), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
        user.id
    }

    /** Owner-only listing for user management. */
    fun listUsers(): List<User> = asOwner { _ -> users.all() }

    fun deactivateUser(id: GlobalId) = asOwner { actor ->
        hubOnly()
        val user = users.byId(id) ?: throw DomainException(DomainError.NotFound("USER"))
        ensure(!(user.role == Role.OWNER && users.all().count { it.role == Role.OWNER && it.isActive } == 1)) { DomainError.InvalidState("USER", "LAST_OWNER") }
        users.save(user.copy(isActive = false))
        trail.append(AuditDraft("USER_DEACTIVATE", "USER", id.value, user.username), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
        id
    }

    /**
     * Changes a user's role and branch grants without touching anything else (AUD-019: editing a user
     * never re-activates them and never rewrites their PIN). The last active owner keeps the owner role.
     */
    fun updateUser(id: GlobalId, role: Role, grants: Set<BranchId>) = asOwner { actor ->
        hubOnly()
        val user = users.byId(id) ?: throw DomainException(DomainError.NotFound("USER"))
        ensure(role != Role.OWNER || grants.isEmpty()) { DomainError.InvalidInput("grants", "مالک به همه شعب دسترسی دارد.") }
        grants.forEach { ensure(branches.byId(it)?.isActive == true) { DomainError.NotFound("BRANCH") } }
        ensure(!(user.role == Role.OWNER && role != Role.OWNER && isLastActiveOwner(user))) { DomainError.InvalidState("USER", "LAST_OWNER") }
        users.save(user.copy(role = role, branchGrants = grants))
        trail.append(AuditDraft("USER_UPDATE", "USER", id.value, "${user.username}:$role:${grants.size}"), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
        id
    }

    fun reactivateUser(id: GlobalId) = asOwner { actor ->
        hubOnly()
        val user = users.byId(id) ?: throw DomainException(DomainError.NotFound("USER"))
        users.save(user.copy(isActive = true, failedAttempts = 0, lock = null, ownerLocked = false))
        trail.append(AuditDraft("USER_REACTIVATE", "USER", id.value, user.username), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
        id
    }

    /** The owner sets a new PIN for a user who forgot theirs; it also clears a login lock. */
    fun resetPin(id: GlobalId, pin: CharArray) = asOwner { actor ->
        hubOnly()
        val user = users.byId(id) ?: throw DomainException(DomainError.NotFound("USER"))
        users.save(user.copy(pinHash = hashValidPin(pin), failedAttempts = 0, lock = null, ownerLocked = false))
        trail.append(AuditDraft("USER_PIN_RESET", "USER", id.value, user.username), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, clock.nowEpochMillis(), epochProvider())
        id
    }

    /**
     * Issues a new one-time recovery code for the signed-in owner, after proving the current PIN (an unlocked
     * phone is not enough), and returns it to be shown once and written down. Only its hash is kept; a new code
     * replaces the old one.
     */
    fun issueRecoveryCode(pin: CharArray): String {
        hubOnly()
        val code = RecoveryCode.generate()
        val (ok, error) = unitOfWork.transaction {
            val actor = session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
            ensure(actor.role == Role.OWNER) { DomainError.PermissionDenied("OWNER_RECOVERY") }
            val user = users.byId(actor.userId)!!
            val now = clock.nowEpochMillis()
            lockError(user, now)?.let { return@transaction false to it }
            if (!PinHasher.verify(pin, user.pinHash)) {
                val failed = failedAttempt(user, now)
                users.save(failed)
                trail.append(AuditDraft("RECOVERY_ISSUE_FAILURE", "USER", user.id.value, "attempts=${failed.failedAttempts}"), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
                return@transaction false to (lockError(failed, now) ?: DomainError.InvalidInput("pin", "رمز فعلی درست نیست."))
            }
            users.save(user.copy(failedAttempts = 0, lock = null, recoveryHash = PinHasher.hash(code.toCharArray())))
            trail.append(AuditDraft("RECOVERY_CODE_ISSUE", "USER", user.id.value, user.username), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
            true to null
        }
        if (!ok) throw DomainException(error!!)
        return RecoveryCode.format(code)
    }

    /**
     * Proves the signed-in user's PIN without changing anything (before enabling fingerprint sign-in, which
     * keeps the PIN sealed on the device). Wrong PINs count like failed logins.
     */
    fun confirmPin(pin: CharArray) {
        val actor = session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        remote?.let { r ->
            try { r.confirmPin(actor.userId, pin) } catch (e: DomainException) { if (e.error is DomainError.InvalidState) session.end(); throw e }
            return
        }
        confirmPinOf(actor.userId, pin, actor)?.let { error ->
            if (error is DomainError.InvalidState) session.end()
            throw DomainException(error)
        }
    }

    /** The main phone's side of [confirmPin] for a person signed in on the second phone (ADR-0042). */
    fun confirmRemotePin(userId: GlobalId, pin: CharArray) {
        hubOnly()
        val actor = users.byId(userId)?.takeIf { it.isActive }?.toActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        confirmPinOf(userId, pin, actor)?.let { throw DomainException(it) }
    }

    private fun confirmPinOf(userId: GlobalId, pin: CharArray, actor: Actor): DomainError? {
        return unitOfWork.transaction {
            val user = users.byId(userId)!!
            val now = clock.nowEpochMillis()
            lockError(user, now)?.let { return@transaction it }
            if (!PinHasher.verify(pin, user.pinHash)) {
                val failed = failedAttempt(user, now)
                users.save(failed)
                trail.append(AuditDraft("PIN_CONFIRM_FAILURE", "USER", user.id.value, "attempts=${failed.failedAttempts}"), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
                return@transaction lockError(failed, now) ?: DomainError.InvalidInput("pin", "رمز فعلی درست نیست.")
            }
            if (user.failedAttempts != 0) users.save(user.copy(failedAttempts = 0, lock = null))
            null
        }
    }

    /** The signed-in user's username (for signing in again on this phone), or null. */
    fun currentUsername(): String? = session.currentActor()?.let { users.byId(it.userId)?.username }

    /** Whether the signed-in user (an owner) has a recovery code. */
    fun hasRecoveryCode(): Boolean {
        val actor = session.currentActor() ?: return false
        return users.byId(actor.userId)?.recoveryHash != null
    }

    /**
     * An owner who forgot the PIN sets a new one with the recovery code. Wrong codes count like wrong PINs (the
     * same growing lock). The code works once: a successful recovery clears it, and a new one should be issued.
     */
    fun recoverOwner(username: String, code: String, newPin: CharArray) {
        hubOnly()
        val error = unitOfWork.transaction {
            val user = users.byUsername(username.trim().lowercase())
            if (user == null || !user.isActive || user.role != Role.OWNER || user.recoveryHash == null) return@transaction DomainError.InvalidInput("recovery", "کد بازیابی یا نام کاربری درست نیست.")
            val now = clock.nowEpochMillis()
            lockError(user, now)?.let { return@transaction it }
            val digits = RecoveryCode.normalize(code)
            if (digits == null || !PinHasher.verify(digits.toCharArray(), user.recoveryHash)) {
                val failed = failedAttempt(user, now)
                users.save(failed)
                trail.append(AuditDraft("RECOVERY_FAILURE", "USER", user.id.value, "attempts=${failed.failedAttempts}"), user.toActor(), ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
                return@transaction lockError(failed, now) ?: DomainError.InvalidInput("recovery", "کد بازیابی یا نام کاربری درست نیست.")
            }
            users.save(user.copy(pinHash = hashValidPin(newPin), failedAttempts = 0, lock = null, ownerLocked = false, recoveryHash = null))
            trail.append(AuditDraft("OWNER_RECOVERED", "USER", user.id.value, user.username), user.toActor(), ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
            null
        }
        if (error != null) throw DomainException(error)
    }

    /** Any signed-in user changes their own PIN by proving the current one. */
    fun changeOwnPin(current: CharArray, next: CharArray): GlobalId {
        hubOnly()
        // Wrong guesses count like failed logins (and survive the failure), so an unlocked phone
        // cannot be used to guess the PIN; after the limit the account locks and the session ends.
        val (id, error) = unitOfWork.transaction {
            val actor = session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
            val user = users.byId(actor.userId)!!
            val now = clock.nowEpochMillis()
            lockError(user, now)?.let { return@transaction null to it }
            if (!PinHasher.verify(current, user.pinHash)) {
                val failed = failedAttempt(user, now)
                users.save(failed)
                trail.append(AuditDraft("PIN_CHANGE_FAILURE", "USER", user.id.value, "attempts=${failed.failedAttempts}"), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
                return@transaction null to (lockError(failed, now) ?: DomainError.InvalidInput("pin", "رمز فعلی درست نیست."))
            }
            users.save(user.copy(pinHash = hashValidPin(next), failedAttempts = 0, lock = null))
            trail.append(AuditDraft("USER_PIN_CHANGE", "USER", user.id.value, user.username), actor, ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
            user.id to null
        }
        if (id == null) {
            if (error is DomainError.InvalidState) session.end()
            throw DomainException(error!!)
        }
        return id
    }

    /** The error for a locked account, or null. */
    private fun lockError(user: User, wall: Long): DomainError? = when {
        user.ownerLocked -> DomainError.InvalidState("USER", "LOCKED_UNTIL_RESET")
        user.lock?.isActive(wall, deviceTime) == true -> DomainError.InvalidState("USER", "LOCKED:${user.lock.remainingSeconds(wall, deviceTime)}")
        else -> null
    }

    /**
     * One more wrong PIN. From the 5th, a growing temporary lock (30 s doubling, at most 15 min); from the
     * [OWNER_RESET_AFTER]th, a non-owner stays locked until the owner resets the PIN — so even a device clock
     * changed across a reboot cannot buy unlimited guesses. The owner (whom nobody else can unlock) keeps the
     * temporary lock only.
     */
    private fun failedAttempt(user: User, wall: Long): User {
        val attempts = user.failedAttempts + 1
        if (attempts >= OWNER_RESET_AFTER && user.role != Role.OWNER) return user.copy(failedAttempts = attempts, ownerLocked = true)
        if (attempts < 5) return user.copy(failedAttempts = attempts)
        val duration = minOf(15 * 60_000L, 30_000L shl minOf(attempts - 5, 5))
        val lock = PinLock(startWall = wall, untilWall = wall + duration, bootId = deviceTime.bootId(), untilElapsed = deviceTime.elapsedMillis() + duration)
        return user.copy(failedAttempts = attempts, lock = lock)
    }

    private fun isLastActiveOwner(user: User) = user.isActive && users.all().count { it.role == Role.OWNER && it.isActive } == 1

    private fun hashValidPin(pin: CharArray): String {
        ensure(pin.size in 6..12 && pin.all { it in '0'..'9' }) { DomainError.InvalidInput("pin", "رمز باید ۶ تا ۱۲ رقم باشد.") }
        return PinHasher.hash(pin)
    }

    fun login(username: String, pin: CharArray): Actor {
        remote?.let { r ->
            val id = r.login(username, pin)
            val user = users.byId(id)?.takeIf { it.isActive } ?: throw DomainException(DomainError.AuthenticationRequired)
            session.start(user.id)
            return user.toActor()
        }
        val user = verifyLogin(username, pin, "")
        session.start(user.id)
        return user.toActor()
    }

    /**
     * The main phone's side of a sign-in on the second phone (ADR-0042): the same PIN check, lock and audit as a
     * sign-in here (the audit names [device]), without signing anyone in on this phone.
     */
    fun verifyRemoteLogin(username: String, pin: CharArray, device: String): User {
        hubOnly()
        return verifyLogin(username, pin, "@" + device.take(40))
    }

    private fun verifyLogin(username: String, pin: CharArray, where: String): User {
        val result = unitOfWork.transaction {
            val user = users.byUsername(username.trim().lowercase()) ?: return@transaction null to DomainError.AuthenticationRequired
            val now = clock.nowEpochMillis()
            if (!user.isActive) return@transaction null to DomainError.AuthenticationRequired
            lockError(user, now)?.let { return@transaction null to it }
            if (!PinHasher.verify(pin, user.pinHash)) {
                val failed = failedAttempt(user, now)
                users.save(failed)
                trail.append(AuditDraft("LOGIN_FAILURE", "USER", user.id.value, "attempts=${failed.failedAttempts}"), user.toActor(), ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
                return@transaction null to DomainError.AuthenticationRequired
            }
            users.save(user.copy(failedAttempts = 0, lock = null))
            trail.append(AuditDraft("LOGIN_SUCCESS", "USER", user.id.value, user.username + where), user.toActor(), ModuleId.PLATFORM, "ORG", GlobalId.new().value, now, epochProvider())
            user to null
        }
        val (user, error) = result
        if (user == null) throw DomainException(error!!)
        return user
    }

    fun logout() = session.end()

    fun accessibleBranches(actor: Actor): List<Branch> =
        branches.all().filter { it.isActive && actor.canAccess(Scope.Branch(it.id)) }

    private fun newUser(username: String, displayName: String, role: Role, grants: Set<BranchId>, pin: CharArray): User {
        val u = username.trim().lowercase()
        ensure(u.matches(Regex("[a-z0-9._-]{3,32}"))) { DomainError.InvalidInput("username", "نام کاربری ۳ تا ۳۲ حرف لاتین یا عدد باشد.") }
        ensure(users.byUsername(u) == null) { DomainError.InvalidState("USER", "DUPLICATE_USERNAME") }
        ensure(displayName.trim().length in 2..60) { DomainError.InvalidInput("displayName", "نام نمایشی الزامی است.") }
        return User(GlobalId.new(), u, displayName.trim(), role, grants, hashValidPin(pin), 0, null, true)
    }

    private fun <T> asOwner(block: (Actor) -> T): T = unitOfWork.transaction {
        val actor = session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        ensure(actor.role.allows(Permission.USER_MANAGE)) { DomainError.PermissionDenied(Permission.USER_MANAGE.name) }
        block(actor)
    }

    companion object {
        /** Wrong PINs after which a non-owner account waits for the owner. */
        const val OWNER_RESET_AFTER = 10
    }
}

/** The owner's recovery code: 16 random digits, shown in four groups («1234-5678-9012-3456»). */
object RecoveryCode {
    private const val DIGITS = 16

    fun generate(): String {
        val random = SecureRandom()
        return buildString { repeat(DIGITS) { append('0' + random.nextInt(10)) } }
    }

    fun format(code: String): String = code.chunked(4).joinToString("-")

    /** Latin, Persian or Arabic digits with any separators → the 16 digits, or null. */
    fun normalize(text: String): String? {
        val digits = text.mapNotNull { c ->
            when (c) {
                in '0'..'9' -> c
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> null
            }
        }.joinToString("")
        return digits.takeIf { it.length == DIGITS }
    }
}
