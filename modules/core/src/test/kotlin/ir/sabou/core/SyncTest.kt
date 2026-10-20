package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.ClosePeriod
import ir.sabou.persistence.SyncLog
import ir.sabou.platform.Command
import ir.sabou.platform.CommandForwarder
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.RemoteIdentity
import ir.sabou.platform.Role
import ir.sabou.platform.StartupVerdict
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.ImmediatePayment
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.jar.JarFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Two phones, one set of books (ADR-0042): routing, serialization and row replication, without the network. */
class SyncTest {
    private val dir: Path = Files.createTempDirectory("sabou-sync")
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val day = BusinessDate(20_000)
    private val open = mutableListOf<ClosableSqlDatabase>()

    private fun boot(name: String): Pair<SabouCore, ClosableSqlDatabase> {
        val db = DevDatabases.open(dir.resolve(name)).also { open += it }
        return SabouCore.open(db, InMemoryAnchorStore(), clock) to db
    }

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @Suppress("UNCHECKED_CAST")
    private fun <T> wire(value: T): T {
        val bytes = ByteArrayOutputStream().also { b -> ObjectOutputStream(b).use { it.writeObject(value) } }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as T
    }

    /** The main phone, a full copy taken from it, and the second phone wired to it in-process. */
    /** The second phone starts from a copy of the main phone's SQLite file (VACUUM INTO): phones only (ADR-0060). */
    private fun phonesOnly() = org.junit.Assume.assumeFalse("phone sync copies an SQLite file", DevDatabases.onPostgres)

    private inner class Pair2 {
        val hub: SabouCore
        val hubDb: ClosableSqlDatabase
        val replica: SabouCore
        val replicaDb: ClosableSqlDatabase
        var token: GlobalId? = null

        init {
            phonesOnly()
            val (h, hd) = boot("hub.db")
            hub = h; hubDb = hd
            hub.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
            hub.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(hub.overview.branches().single().id), "222222".toCharArray())
            val copy = dir.resolve("replica.db").toString()
            val cursor = hub.exclusive {
                val c = hub.sync.head()
                hubDb.execute("VACUUM INTO '$copy'")
                c
            }
            hub.identity.logout()
            val (r, rd) = boot("replica.db")
            replica = r; replicaDb = rd
            replica.sync.startAt(cursor)
            replica.sync.becomeReplica(
                CommandForwarder { _, command -> forward(command) },
                object : RemoteIdentity {
                    override fun login(username: String, pin: CharArray): GlobalId =
                        hub.sync.verifyLogin(username, pin, "phone-2").id.also { token = it; replicate() }
                    override fun confirmPin(userId: GlobalId, pin: CharArray) = hub.sync.confirmPin(userId, pin)
                },
            )
        }

        fun forward(command: Command): CommandOutcome {
            val outcome = try {
                wire(hub.sync.execute(token ?: throw DomainException(DomainError.AuthenticationRequired), wire(command)))
            } catch (e: DomainException) {
                throw DomainException(wire(e.error))
            }
            replicate()
            return outcome
        }

        fun replicate() = replica.sync.apply(wire(hub.sync.changesSince(replica.sync.cursor())))
    }

    /** Every replicated table, row by row, in a comparable form. */
    private fun dump(db: ClosableSqlDatabase): Map<String, List<String>> = SyncLog.tables(db).associateWith { t ->
        db.query("SELECT * FROM $t").map { row ->
            SyncLog.columns(db, t).joinToString("|") { c -> row.raw(c.name).let { if (it is ByteArray) it.joinToString(",") else it.toString() } }
        }.sorted()
    }

    @Test fun aDayOnTheSecondPhoneLeavesBothPhonesWithIdenticalBooks() {
        val p = Pair2()
        val actor = p.replica.identity.login("owner", "123456".toCharArray())
        assertEquals(Role.OWNER, actor.role)
        val branch = Scope.Branch(p.replica.overview.branches().single().id)
        val cash = p.replica.overview.treasury().single().account.id

        // Everything below runs on the main phone, as the owner signed in on the second phone.
        p.replica.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(200_000_000), day, "آورده"))
        val cheese = p.replica.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.units(2))).resultId
        val kitchen = p.replica.overview.locations(branch).single().id
        val pizza = p.replica.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        p.replica.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        val supplier = p.replica.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        p.replica.purchasing.postInvoice(
            PostPurchaseInvoice(id(), branch, supplier, "۱۲۳", kitchen, day, day.plusDays(30),
                listOf(InvoiceLine(cheese, Quantity.units(10), rial(30_000_000))), ImmediatePayment(cash, rial(10_000_000))),
        )
        val customer = p.replica.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(50_000_000))).resultId
        val draft = p.replica.salesOps.saveDraft(
            SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(pizza, Quantity.units(20), rial(600_000))),
                rial(1_000_000), rial(0), rial(990_000),
                listOf(Settlement.Liquid(cash, rial(8_990_000)), Settlement.Credit(customer, rial(3_000_000), day.plusDays(10))), discountReason = "مشتری دائمی"),
        ).resultId
        p.replica.salesOps.post(PostDailySale(id(), branch, draft))

        // The second phone reads its own copy, which already shows its commands' effects.
        p.hub.identity.login("owner", "123456".toCharArray())
        assertEquals(p.hub.overview.treasury().map { it.balance }, p.replica.overview.treasury().map { it.balance })
        assertTrue(p.replica.overview.treasury().single().balance > 0)

        // Meanwhile the main phone's own user works too; the second phone copies it on its next pull.
        p.hub.treasury.openAccount(OpenTreasuryAccount(id(), branch, "کارت‌خوان ملت", TreasuryKind.CARD_TERMINAL))
        p.replicate()

        assertEquals(dump(p.hubDb), dump(p.replicaDb))
        // The copy's audit chain is the main phone's chain, and verifies on its own.
        assertEquals(StartupVerdict.Healthy, p.replica.verifyAuditFull())
        val hubAudit = p.hubDb.query("SELECT hash FROM audit_events ORDER BY position").map { it.str("hash") }
        assertEquals(hubAudit, p.replicaDb.query("SELECT hash FROM audit_events ORDER BY position").map { it.str("hash") })
        // The sign-in on the second phone is in the main phone's audit, naming the phone.
        assertTrue(p.hubDb.query("SELECT detail FROM audit_events WHERE action = 'LOGIN_SUCCESS'").any { it.str("detail") == "owner@phone-2" })
    }

    /** ADR-0062 on two phones: records, actions and scorecard settings made on the second phone are the same on both. */
    @Test fun scorecardRecordsAndSettingsFromTheSecondPhoneAreOnBoth() {
        val p = Pair2()
        p.replica.identity.login("owner", "123456".toCharArray())
        val branch = Scope.Branch(p.replica.overview.branches().single().id)
        p.replica.assessments.record(RecordField(id(), branch, FieldRecord.Kind.TEMPERATURE, day, "یخچال", 40, 0))
        p.replica.assessments.openAction(OpenAssessmentAction(id(), branch, "FOOD_COST", "D03.FOOD", "بهای غذا", "رسپی‌ها بازبینی شود", "مدیر", day.plusDays(7), 0, day))
        p.replica.assessments.setSettings(SetAssessmentSettings(id(), mapOf("D03.WASTE" to listOf(200L, 500L)), emptyMap(), mapOf("ONLINE" to 2_000L)))
        p.hub.identity.login("owner", "123456".toCharArray())
        assertEquals(p.hub.assessments.records(branch, FieldRecord.Kind.TEMPERATURE), p.replica.assessments.records(branch, FieldRecord.Kind.TEMPERATURE))
        assertEquals(1, p.replica.assessments.actions(branch).size)
        assertEquals(p.hub.assessments.settings(), p.replica.assessments.settings())
        p.replicate()                                                                   // the main phone's own sign-in, too
        assertEquals(dump(p.hubDb), dump(p.replicaDb))
        assertEquals(StartupVerdict.Healthy, p.replica.verifyAuditFull())
    }

    @Test fun aRetriedCommandRunsOnceAndAnswersWithItsFirstResult() {
        val p = Pair2()
        p.replica.identity.login("owner", "123456".toCharArray())
        val branch = Scope.Branch(p.replica.overview.branches().single().id)
        val command = OpenTreasuryAccount(id(), branch, "بانک ملت", TreasuryKind.BANK)
        val first = p.replica.treasury.openAccount(command)
        val again = p.replica.treasury.openAccount(command)
        assertEquals(first.resultId, again.resultId)
        assertTrue(again.replayed)
        assertEquals(2, p.replica.overview.treasury().size)
        assertEquals(1, p.hubDb.query("SELECT COUNT(*) AS n FROM treasury_accounts WHERE id = ?", first.resultId.value).single().long("n"))
    }

    @Test fun permissionsAreCheckedOnBothPhonesForThePersonSignedInOnTheSecond() {
        val p = Pair2()
        p.replica.identity.login("cashier", "222222".toCharArray())
        val branch = Scope.Branch(p.replica.overview.branches().single().id)
        val e = assertFailsWith<DomainException> { p.replica.treasury.openAccount(OpenTreasuryAccount(id(), branch, "بانک", TreasuryKind.BANK)) }
        assertTrue(e.error is DomainError.PermissionDenied)
        // Even a command that got past the second phone runs on the main phone for that person only.
        val cashier = p.hub.users.byUsername("cashier")!!.id
        val e2 = assertFailsWith<DomainException> { p.hub.sync.execute(cashier, OpenTreasuryAccount(id(), branch, "بانک", TreasuryKind.BANK)) }
        assertTrue(e2.error is DomainError.PermissionDenied)
        // A deactivated person can no longer act through the second phone.
        p.hub.identity.login("owner", "123456".toCharArray())
        p.hub.identity.deactivateUser(cashier)
        val e3 = assertFailsWith<DomainException> { p.hub.sync.execute(cashier, OpenTreasuryAccount(id(), branch, "بانک", TreasuryKind.BANK)) }
        assertEquals(DomainError.AuthenticationRequired, e3.error)
    }

    @Test fun wrongPinsOnTheSecondPhoneCountOnTheMainPhone() {
        val p = Pair2()
        val e = assertFailsWith<DomainException> { p.replica.identity.login("owner", "999999".toCharArray()) }
        assertEquals(DomainError.AuthenticationRequired, e.error)
        assertEquals(1, p.hub.users.byUsername("owner")!!.failedAttempts)
        assertEquals(null, p.replica.session.currentActor())
    }

    @Test fun theSecondPhoneNeverWritesItsOwnRecords() {
        val p = Pair2()
        p.replica.identity.login("owner", "123456".toCharArray())
        val branch = p.replica.overview.branches().single().id
        for (attempt in listOf<() -> Unit>(
            { p.replica.identity.createUser("x1x", "کاربر", Role.CASHIER, setOf(branch), "333333".toCharArray()) },
            { p.replica.identity.changeOwnPin("123456".toCharArray(), "654321".toCharArray()) },
            { p.replica.identity.createBranch("شعبه دو") },
            { p.replica.authorizeBackup() },
        )) {
            val e = assertFailsWith<DomainException> { attempt() }
            assertEquals("INVALID_STATE:DEVICE:HUB_ONLY", e.error.code)
        }
        p.replicate()
        assertEquals(dump(p.hubDb), dump(p.replicaDb))
    }

    @Test fun deletesAndKeyChangesReplicate() {
        val p = Pair2()
        p.hubDb.execute("INSERT INTO meta(key, value) VALUES ('t.a', '1'), ('t.b', '2'), ('t.c', '3')")
        p.replicate()
        p.hubDb.execute("UPDATE meta SET value = '10' WHERE key = 't.a'")
        p.hubDb.execute("DELETE FROM meta WHERE key = 't.b'")
        p.hubDb.execute("UPDATE meta SET key = 't.d' WHERE key = 't.c'")
        p.hubDb.execute("INSERT INTO meta(key, value) VALUES ('t.b', 'again')")
        p.replicate()
        val rows = p.replicaDb.query("SELECT key, value FROM meta WHERE key LIKE 't.%' ORDER BY key").map { it.str("key") + "=" + it.str("value") }
        assertEquals(listOf("t.a=10", "t.b=again", "t.d=3"), rows)
        assertEquals(dump(p.hubDb), dump(p.replicaDb))
    }

    @Test fun aCursorOlderThanThePrunedLogAsksForAFullCopy() {
        val p = Pair2()
        p.hubDb.execute("INSERT INTO meta(key, value) VALUES ('t.a', '1')")
        val head = p.hub.sync.head()
        p.hub.sync.prune(head)
        assertTrue(p.hub.sync.changesSince(head - 1).tooMany)
        assertTrue(!p.hub.sync.changesSince(head).tooMany)
        // A cursor beyond the log: the main phone's books were replaced (e.g. restored from a backup).
        assertTrue(p.hub.sync.changesSince(head + 100).tooMany)
    }

    @Test fun monthCloseFromTheSecondPhoneRunsTheChecklistOnTheMainPhone() {
        val (core, _) = boot("one.db")
        assertEquals(Closing::class.java, core.sync.dispatcher.routeOwner(ClosePeriod::class.java))
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val from = JalaliCalendar.date(1405, 1, 1)
        // Not a whole month: refused, whereas the ledger alone would lock any range.
        assertFailsWith<DomainException> { core.sync.execute(core.users.byUsername("owner")!!.id, ClosePeriod(id(), from, from.plusDays(3))) }
    }

    @Test fun everyCommandHasExactlyOneRoute() {
        val (core, _) = boot("routes.db")
        val missing = commandClasses().filter { it !in core.sync.dispatcher.commandTypes }.map { it.name }
        assertEquals(emptyList(), missing, "commands the main phone could not run for the second phone")
    }

    @Test fun everyCommandCanTravelBetweenThePhones() {
        val all = scanned
        val problems = commandClasses().flatMap { serializableProblems(it, all, HashSet()) }.distinct()
        assertEquals(emptyList(), problems)
    }

    // ------------------------------------------------------------------ class scanning

    private val scanned: List<Class<*>> by lazy {
        val loader = javaClass.classLoader
        // Main code only: test sources declare commands of their own.
        System.getProperty("java.class.path").split(File.pathSeparator).filter { !isTestOutput(it) }.flatMap { entry ->
            val f = File(entry)
            val names = when {
                f.isDirectory -> f.walkTopDown().filter { it.name.endsWith(".class") }.map { it.relativeTo(f).path }.toList()
                f.name.endsWith(".jar") && f.exists() -> JarFile(f).use { j -> j.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
                else -> emptyList()
            }
            names.filter { it.startsWith("ir/sabou/") || it.startsWith("ir\\sabou\\") }
                .map { it.removeSuffix(".class").replace('/', '.').replace('\\', '.') }
        }.distinct().mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { !it.name.endsWith("Test") && !it.name.contains("Test$") && !it.name.contains(".memory.") }
    }

    private fun commandClasses(): List<Class<*>> = scanned.filter {
        Command::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) && !it.isAnonymousClass
    }

    private fun serializableProblems(type: Type, all: List<Class<*>>, seen: MutableSet<Type>): List<String> {
        if (!seen.add(type)) return emptyList()
        return when (type) {
            is ParameterizedType -> serializableProblems(type.rawType, all, seen) + type.actualTypeArguments.flatMap { serializableProblems(it, all, seen) }
            is WildcardType -> type.upperBounds.flatMap { serializableProblems(it, all, seen) }
            is Class<*> -> when {
                type.isPrimitive || type == String::class.java || type.isEnum -> emptyList()
                type.isArray -> serializableProblems(type.componentType, all, seen)
                type == java.util.List::class.java || type == java.util.Set::class.java || type == java.util.Map::class.java ||
                    type == java.util.Collection::class.java -> emptyList()
                type == java.lang.Object::class.java -> listOf("untyped field (Object)")
                !java.io.Serializable::class.java.isAssignableFrom(type) -> listOf("${type.name} is not Serializable")
                type.isInterface || Modifier.isAbstract(type.modifiers) ->
                    all.filter { type.isAssignableFrom(it) && it != type && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
                        .flatMap { serializableProblems(it, all, seen) }
                type.name.startsWith("java.") || type.name.startsWith("kotlin.") -> emptyList()
                else -> generateSequence(type as Class<*>?) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { c ->
                    c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }.asSequence()
                }.toList().flatMap { f -> serializableProblems(f.genericType, all, seen).map { "${type.simpleName}.${f.name}: $it" } }
            }
            else -> emptyList()
        }
    }
}

/**
 * A class-path entry holding compiled tests: Gradle's `…/classes/kotlin/test` or `…/resources/test` folders, or the
 * offline build's `*-test.jar`. Judged by whole path segments, so a checkout in a folder named e.g. «latest» or
 * «contest» is not mistaken for one.
 */
internal fun isTestOutput(entry: String): Boolean {
    val f = File(entry)
    if (f.name.endsWith("-test.jar")) return true
    return generateSequence(f) { it.parentFile }.any { it.name == "test" && it.parentFile?.name in setOf("kotlin", "java", "resources") }
}
