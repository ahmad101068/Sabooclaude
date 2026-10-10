package ir.sabou.assets

import ir.sabou.assets.memory.InMemoryAssetStore
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryDocumentNumberStore
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryGateway
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.TreasuryOperations
import ir.sabou.treasury.memory.InMemoryMovementStore
import ir.sabou.treasury.memory.InMemoryTreasuryAccountStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetsTest {
    private val branch = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val movements = InMemoryMovementStore()
    private val accounts = InMemoryTreasuryAccountStore()
    private val store = InMemoryAssetStore()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }, numbers) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val tCap = registry.issue(ModuleId.TREASURY)
    private val treasury = TreasuryGateway(ledger, tCap, accounts, movements)
    private val treasuryOps = TreasuryOperations(bus, treasury, tCap, accounts)
    private val ops = AssetOperations(bus, ledger, registry.issue(ModuleId.ASSETS), treasury, store)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, movements, accounts, store) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun rial(v: Long) = Money.of(v)
    private fun gl(code: ir.sabou.ledger.AccountCode) = ledger.balance(code, branch).rial
    private val bank = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branch, "بانک", TreasuryKind.BANK)).resultId
    init { treasuryOps.receipt(RecordReceipt(GlobalId.new(), branch, bank, ReceiptPurpose.OWNER_CAPITAL, rial(1_000_000_000), day, "آورده")) }

    private fun buyOven(cost: Long = 120_000_000, salvage: Long = 0, months: Int = 120) = ops.acquire(AcquireAsset(
        GlobalId.new(), branch, "فر پیتزا", "تجهیزات آشپزخانه", rial(cost), rial(salvage), day, DepreciationMethod.STRAIGHT_LINE, months, null,
        Funding.Paid(bank),
    )).resultId

    @Test fun straightLineSpreadsEvenlyAndNeverGoesBelowSalvage() {
        val oven = buyOven(cost = 120_000_000, salvage = 12_000_000, months = 120)
        assertEquals(120_000_000, gl(StandardAccounts.FIXED_ASSETS))
        assertEquals(880_000_000, treasury.balance(bank))
        // One year ≈ 10% of (cost − salvage).
        ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(364)))
        val year = store.byId(oven)!!.accumulated.rial
        assertEquals(true, year in 10_790_000..10_810_000)
        assertEquals(year, gl(StandardAccounts.DEPRECIATION))
        assertEquals(-year, gl(StandardAccounts.ACCUMULATED_DEPRECIATION))
        // Running again for the same day books nothing.
        assertEquals("INVALID_STATE:DEPRECIATION:NOTHING_TO_BOOK", code { ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(364))) })
        // Far beyond its life it stops exactly at salvage.
        ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(20 * 365)))
        assertEquals(108_000_000, store.byId(oven)!!.accumulated.rial)
        assertEquals(12_000_000, store.byId(oven)!!.bookValue.rial)
    }

    @Test fun decliningBalanceTakesARateOfWhatIsLeft() {
        val van = ops.acquire(AcquireAsset(GlobalId.new(), branch, "وانت", "وسایل نقلیه", rial(1_000_000_000), rial(0), day,
            DepreciationMethod.DECLINING_BALANCE, null, 2_500, Funding.Existing(rial(0), null))).resultId
        assertEquals(1_000_000_000, gl(StandardAccounts.FIXED_ASSETS))
        assertEquals(-2_000_000_000, gl(StandardAccounts.CAPITAL))           // the owner's funding plus the van
        ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(364)))
        assertEquals(250_000_000, store.byId(van)!!.accumulated.rial)
        ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(729)))
        assertEquals(437_500_000, store.byId(van)!!.accumulated.rial)         // 25% of 750,000,000 more
    }

    @Test fun onlyTheLatestRunCanBeTakenBack() {
        val oven = buyOven()
        val first = ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(29))).resultId
        val second = ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(59))).resultId
        assertEquals("INVALID_STATE:DEPRECIATION_RUN:NOT_LATEST", code { ops.reverseRun(ReverseDepreciationRun(GlobalId.new(), branch, first, day.plusDays(60), "اشتباه")) })
        val afterFirst = store.runs().single { it.id == first }.total.rial
        ops.reverseRun(ReverseDepreciationRun(GlobalId.new(), branch, second, day.plusDays(60), "ماه اشتباه"))
        assertEquals(afterFirst, store.byId(oven)!!.accumulated.rial)
        assertEquals(day.plusDays(29), store.byId(oven)!!.depreciatedThrough)
        assertEquals(afterFirst, gl(StandardAccounts.DEPRECIATION))
    }

    @Test fun disposalBooksTheLastDepreciationAndTheGainOrLoss() {
        val oven = buyOven(cost = 120_000_000, salvage = 0, months = 120)
        ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(364)))
        ops.dispose(DisposeAsset(GlobalId.new(), branch, oven, day.plusDays(729), rial(100_000_000), bank, "فروش"))
        val asset = store.byId(oven)!!
        assertEquals(AssetStatus.DISPOSED, asset.status)
        val book = 120_000_000 - asset.accumulated.rial
        assertEquals(0, gl(StandardAccounts.FIXED_ASSETS))
        assertEquals(0, gl(StandardAccounts.ACCUMULATED_DEPRECIATION))
        assertEquals(100_000_000 - book, -gl(StandardAccounts.OTHER_INCOME) - gl(StandardAccounts.OTHER_EXPENSE))
        assertEquals(0, gl(StandardAccounts.INTER_BRANCH))
        assertEquals(980_000_000, treasury.balance(bank))
        assertEquals("INVALID_STATE:FIXED_ASSET:DISPOSED", code { ops.dispose(DisposeAsset(GlobalId.new(), branch, oven, day.plusDays(800), rial(0), null, "دوباره")) })
    }

    @Test fun existingAssetsComeInWithTheirDepreciationSoFar() {
        val old = ops.acquire(AcquireAsset(GlobalId.new(), branch, "یخچال", "تجهیزات", rial(60_000_000), rial(0), day, DepreciationMethod.STRAIGHT_LINE, 60, null,
            Funding.Existing(rial(12_000_000), day.plusDays(364)))).resultId
        assertEquals(-12_000_000, gl(StandardAccounts.ACCUMULATED_DEPRECIATION))
        assertEquals(-1_048_000_000, gl(StandardAccounts.CAPITAL))          // funding plus the fridge's net value
        assertEquals("INVALID_INPUT:accumulated", code {
            ops.acquire(AcquireAsset(GlobalId.new(), branch, "میز", "", rial(10), rial(0), day, DepreciationMethod.STRAIGHT_LINE, 6, null, Funding.Existing(rial(11), day)))
        })
        ops.depreciate(RunDepreciation(GlobalId.new(), branch, day.plusDays(729)))
        val acc = store.byId(old)!!.accumulated.rial
        assertEquals(true, acc in 23_980_000..24_020_000)                          // two of five years
    }
}
