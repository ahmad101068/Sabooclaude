package ir.sabou.core

import ir.sabou.assets.AcquireAsset
import ir.sabou.assets.DepreciationMethod
import ir.sabou.assets.Funding
import ir.sabou.assets.TransferAsset
import ir.sabou.assets.UpdateAsset
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.RecordOpeningStock
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.RequestStock
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.TransferStock
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Review of the other modules, stage 4 (ADR-0045): assets on credit, moved and renamed; the kitchen role. */
class AssetsAndRolesTest {
    private val dir = Files.createTempDirectory("assetsroles")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("a.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private var branch = Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.new()))
    private var cash = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun start() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        cash = core.overview.treasury().single().account.id
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(100_000_000), day, "آورده"))
    }

    @Test fun anAssetBoughtInInstalmentsIsPaidOffAndMovedToAnotherBranch() {
        start()
        val oven = core.fixedAssets.acquire(AcquireAsset(id(), branch, "فر پیتزا", "تجهیزات", rial(60_000_000), Money.ZERO, day,
            DepreciationMethod.STRAIGHT_LINE, 60, null, Funding.OnCredit("فروشگاه تجهیزات"))).resultId
        assertEquals(-60_000_000L, core.ledger.balance(StandardAccounts.ASSET_PURCHASE_PAYABLE, branch).rial)
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, rial(20_000_000), day.plusDays(30), "قسط اول فر",
            counterAccount = StandardAccounts.ASSET_PURCHASE_PAYABLE))
        assertEquals(-40_000_000L, core.ledger.balance(StandardAccounts.ASSET_PURCHASE_PAYABLE, branch).rial)
        core.fixedAssets.update(UpdateAsset(id(), branch, oven, "فر پیتزای سنگی", "آشپزخانه"))
        val other = Scope.Branch(core.identity.createBranch("شعبه دو"))
        core.fixedAssets.transfer(TransferAsset(id(), branch, oven, other, day.plusDays(1)))
        assertEquals(0L, core.ledger.balance(StandardAccounts.FIXED_ASSETS, branch).rial)
        assertEquals(60_000_000L, core.ledger.balance(StandardAccounts.FIXED_ASSETS, other).rial)
        assertEquals(other, core.assetStore.byId(oven)!!.scope)
        assertEquals("فر پیتزای سنگی", core.assetStore.byId(oven)!!.name)
    }

    @Test fun theKitchenAsksForGoodsAndRecordsWasteButDoesNotMoveOrBuy() {
        start()
        val kitchen = core.overview.locations(branch).single().id
        val store = core.inventory.createLocation(CreateLocation(id(), branch, "انبار")).resultId
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        core.inventory.openingStock(RecordOpeningStock(id(), branch, store, listOf(ReceiptLine(cheese, Quantity.units(5), rial(5_000_000))), day))
        core.inventory.openingStock(RecordOpeningStock(id(), branch, kitchen, listOf(ReceiptLine(cheese, Quantity.units(1), rial(1_000_000))), day))
        core.identity.createUser("cook", "آشپز", Role.KITCHEN, setOf(branch.branchId), "111111".toCharArray())
        core.identity.login("cook", "111111".toCharArray())
        core.inventory.requestStock(RequestStock(id(), branch, store, kitchen, listOf(IssueLine(cheese, Quantity.units(2))), "", day))
        core.inventory.waste(RecordWaste(id(), branch, kitchen, cheese, Quantity.of(200_000), WasteReason.SPOILAGE, "", day))
        assertTrue(code { core.inventory.transfer(TransferStock(id(), branch, store, kitchen, listOf(IssueLine(cheese, Quantity.units(1))), day, "")) }.startsWith("PERMISSION_DENIED"))
        assertTrue(code { core.overview.invoices() }.startsWith("PERMISSION_DENIED"))
    }
}
