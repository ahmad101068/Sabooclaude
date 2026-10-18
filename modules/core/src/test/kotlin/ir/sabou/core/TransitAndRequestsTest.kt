package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CloseStockRequest
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.FulfilStockRequest
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.ReceiveTransfer
import ir.sabou.inventory.RecordOpeningStock
import ir.sabou.inventory.RequestStock
import ir.sabou.inventory.ReverseInventoryDocument
import ir.sabou.inventory.StockRequest
import ir.sabou.inventory.StockTransit
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.TransferStock
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Inventory review, stage 2 (ADR-0044): transfers between branches in transit, and requests for goods. */
class TransitAndRequestsTest {
    private val dir = Files.createTempDirectory("transit")
    private val db = DevDatabases.open(dir.resolve("t.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)
    private fun id() = GlobalId.new()
    private fun kg(n: Long) = Quantity.units(n)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private var branch = Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.new()))
    private var other = branch
    private var kitchen = GlobalId.new()
    private var store = GlobalId.new()
    private var otherKitchen = GlobalId.new()
    private var cheese = GlobalId.new()

    private fun start() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        store = core.inventory.createLocation(CreateLocation(id(), branch, "انبار مرکزی")).resultId
        other = Scope.Branch(core.identity.createBranch("شعبه دو"))
        otherKitchen = core.overview.locations(other).firstOrNull()?.id ?: core.inventory.createLocation(CreateLocation(id(), other, "آشپزخانه دو")).resultId
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        core.inventory.openingStock(RecordOpeningStock(id(), branch, store, listOf(ReceiptLine(cheese, kg(10), Money.of(10_000_000))), day))
    }

    private fun qty(location: GlobalId) = core.stock.balance(cheese, location).quantity
    private fun balance(account: ir.sabou.ledger.AccountCode, scope: Scope.Branch) = core.ledger.balance(account, scope).rial

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun goodsInTransitEnterOnlyWhenReceivedAndWhatIsMissingIsTheReceiversVariance() {
        start()
        val t = core.inventory.transfer(TransferStock(id(), branch, store, otherKitchen, listOf(IssueLine(cheese, kg(4))), day, "", inTransit = true)).resultId
        assertEquals(kg(6), qty(store))
        assertEquals(Quantity.ZERO, qty(otherKitchen))
        assertEquals(4_000_000, balance(StandardAccounts.INTER_BRANCH, branch))
        assertEquals(0, balance(StandardAccounts.INVENTORY, other))
        val incoming = core.stockRecords.transits(other).single()
        assertTrue(incoming.incoming && incoming.transit.status == StockTransit.Status.IN_TRANSIT)
        assertTrue(!core.stockRecords.transits(branch).single().incoming)
        // The sender cannot receive its own transfer.
        assertEquals("INVALID_INPUT:scope", code { core.inventory.receiveTransfer(ReceiveTransfer(id(), branch, t, emptyList(), day)) })
        // Half a kilo did not arrive.
        assertEquals("INVALID_INPUT:received", code { core.inventory.receiveTransfer(ReceiveTransfer(id(), other, t, listOf(IssueLine(cheese, kg(5))), day)) })
        core.inventory.receiveTransfer(ReceiveTransfer(id(), other, t, listOf(IssueLine(cheese, Quantity.of(3_500_000))), day.plusDays(1), "یک بسته کم بود"))
        assertEquals(Quantity.of(3_500_000), qty(otherKitchen))
        assertEquals(3_500_000, balance(StandardAccounts.INVENTORY, other))
        assertEquals(500_000, balance(StandardAccounts.INVENTORY_VARIANCE, other))
        assertEquals(-4_000_000, balance(StandardAccounts.INTER_BRANCH, other))
        assertEquals(StockTransit.Status.RECEIVED, core.stockRecords.transits(other).single().transit.status)
        assertEquals("TRANSFER:ALREADY_RECEIVED", "TRANSFER:" + code { core.inventory.receiveTransfer(ReceiveTransfer(id(), other, t, emptyList(), day.plusDays(1))) }.substringAfterLast(':'))
        // Once received, the sender can no longer take it back.
        assertTrue(code { core.inventory.reverseDocument(ReverseInventoryDocument(id(), branch, InventoryOperations.TRANSFER, t, day.plusDays(1), "اشتباه")) }.endsWith("ALREADY_RECEIVED"))
        assertTrue(core.stockRecords.documents(other, day, day.plusDays(1)).any { it.document.type == InventoryOperations.TRANSFER_RECEIPT })
    }

    @Test fun aTransferStillOnTheWayCanBeTakenBack() {
        start()
        val t = core.inventory.transfer(TransferStock(id(), branch, store, otherKitchen, listOf(IssueLine(cheese, kg(4))), day, "", inTransit = true)).resultId
        core.inventory.reverseDocument(ReverseInventoryDocument(id(), branch, InventoryOperations.TRANSFER, t, day, "مقصد اشتباه"))
        assertEquals(kg(10), qty(store))
        assertEquals(0, balance(StandardAccounts.INTER_BRANCH, branch))
        assertEquals(StockTransit.Status.CANCELLED, core.stockRecords.transits(other).single().transit.status)
        assertTrue(code { core.inventory.receiveTransfer(ReceiveTransfer(id(), other, t, emptyList(), day)) }.endsWith("CANCELLED"))
    }

    @Test fun theKitchenRequestsAndTheStorekeeperSendsWhatHeHas() {
        start()
        core.identity.createUser("cook", "آشپز", Role.STOREKEEPER, setOf(branch.branchId), "111111".toCharArray())
        core.identity.login("cook", "111111".toCharArray())
        val r = core.inventory.requestStock(RequestStock(id(), branch, store, kitchen, listOf(IssueLine(cheese, kg(3))), "برای شام", day)).resultId
        val asked = core.stockRecords.requests(branch).single()
        assertEquals("آشپز", asked.request.requestedBy)
        assertEquals(kg(10), asked.lines.single().onHand)
        // A refusal needs its reason.
        assertEquals("INVALID_INPUT:reason", code { core.inventory.closeRequest(CloseStockRequest(id(), branch, r, "")) })
        core.identity.login("owner", "123456".toCharArray())
        core.inventory.fulfilRequest(FulfilStockRequest(id(), branch, r, listOf(IssueLine(cheese, kg(2))), day))
        assertEquals(kg(2), qty(kitchen))
        val sent = core.stockRecords.requests(branch).single()
        assertEquals(StockRequest.Status.SENT, sent.request.status)
        assertTrue(sent.transferNumber != null)
        assertTrue(code { core.inventory.fulfilRequest(FulfilStockRequest(id(), branch, r, listOf(IssueLine(cheese, kg(1))), day)) }.endsWith("NOT_OPEN"))
        // A second request is withdrawn by the one who made it.
        core.identity.login("cook", "111111".toCharArray())
        val r2 = core.inventory.requestStock(RequestStock(id(), branch, store, kitchen, listOf(IssueLine(cheese, kg(1))), "", day)).resultId
        core.inventory.closeRequest(CloseStockRequest(id(), branch, r2, "", cancel = true))
        assertEquals(StockRequest.Status.CANCELLED, core.stockRecords.requests(branch).first { it.request.id == r2 }.request.status)
    }

    @Test fun aBranchRequestsFromAnotherBranchsStoreroomAndReceivesIt() {
        start()
        val r = core.inventory.requestStock(RequestStock(id(), other, store, otherKitchen, listOf(IssueLine(cheese, kg(2))), "", day)).resultId
        assertTrue(core.stockRecords.requests(branch).single().toSend)
        assertTrue(!core.stockRecords.requests(other).single().toSend)
        val t = core.inventory.fulfilRequest(FulfilStockRequest(id(), branch, r, listOf(IssueLine(cheese, kg(2))), day)).resultId
        assertEquals(Quantity.ZERO, qty(otherKitchen))
        core.inventory.receiveTransfer(ReceiveTransfer(id(), other, t, emptyList(), day))
        assertEquals(kg(2), qty(otherKitchen))
        assertEquals(0, balance(StandardAccounts.INVENTORY_VARIANCE, other))
    }
}
