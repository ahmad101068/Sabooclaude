package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission

/**
 * Something a person should act on, announced on their phone (ADR-0024). [key] names the situation, not the
 * moment: the same low stock keeps the same key until it is resolved, so it is announced once — and again
 * only if it comes back.
 */
data class Alert(val key: String, val kind: Kind, val title: String, val text: String) {
    enum class Kind { LOW_STOCK, PRICE_CHANGE, CHEQUE_DUE, SHORTAGE, APPROVAL, FOOD_COST }
}

/**
 * The alerts of one user, computed with that user's own permissions and branches through the ordinary read
 * models (as a read-only stand-in on the calling thread; commands are refused meanwhile). Used by the phone's
 * background check, so nobody needs to be signed in.
 */
class Alerts internal constructor(private val core: SabouCore) {
    fun forUser(userId: GlobalId, today: BusinessDate): List<Alert> {
        val user = core.users.byId(userId)?.takeIf { it.isActive } ?: return emptyList()
        val actor = user.toActor()
        return core.session.readAs(actor) {
            buildList {
                if (actor.role.allows(Permission.INVENTORY_VIEW)) {
                    safe { core.overview.lowStock() }.forEach { low ->
                        add(Alert("LOW:${low.item.id.value}:${low.location.id.value}", Alert.Kind.LOW_STOCK, "کمبود «${low.item.name}»",
                            "${low.location.name}: ${Fa.quantity(low.quantity)} مانده، کمتر از حداقل ${Fa.quantity(low.item.minimumStock)}"))
                    }
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.overview.openShortages(Scope.Branch(b.id)) }.forEach { v ->
                            add(Alert("SHORT:${v.shortage.id.value}", Alert.Kind.SHORTAGE, "کسری مصرف «${v.item.name}»",
                                "فروش ${Fa.quantity(v.shortage.open)} بیش از موجودی دفتری ${v.location.name} مصرف کرد؛ با ثبت خرید یا انبارگردانی بسته می‌شود."))
                        }
                    }
                }
                if (actor.role.allows(Permission.PURCHASE_VIEW)) {
                    safe { core.buying.priceChanges(today.plusDays(-PRICE_DAYS), today, PRICE_THRESHOLD_BP) }.forEach { c ->
                        val bp = c.changeBp
                        add(Alert("PRICE:${c.invoiceId.value}:${c.item.id.value}", Alert.Kind.PRICE_CHANGE,
                            "${if ((bp ?: 1) > 0) "گران‌تر" else "ارزان‌تر"}: «${c.item.name}» ${bp?.let { (if (it > 0) "+" else "−") + Fa.percent(kotlin.math.abs(it)) } ?: ""}".trim(),
                            "${c.supplier}: ${Fa.rial(c.price)} ریال؛ قبلی ${Fa.rial(c.previousPrice)} از ${c.previousSupplier}"))
                    }
                }
                if (actor.role.allows(Permission.TREASURY_VIEW) || actor.role.allows(Permission.CHEQUE_MANAGE)) {
                    safe { core.books.chequesDue(today, CHEQUE_DAYS) }.forEach { r ->
                        val c = r.cheque
                        val overdue = c.dueDate < today
                        // The key changes when a due cheque becomes overdue, so that step is announced too.
                        add(Alert("CHEQUE:${c.id.value}:${if (overdue) "late" else "due"}", Alert.Kind.CHEQUE_DUE,
                            "${if (c.direction == ir.sabou.treasury.ChequeDirection.RECEIVED) "چک دریافتی" else "چک پرداختی"} ${if (overdue) "سررسید گذشته" else "نزدیک سررسید"}",
                            "${Fa.rial(c.amount)} ریال · ${c.details.counterparty} · ${Fa.date(c.dueDate)}"))
                    }
                }
                // A dish whose recipe now costs more than the target share of its price (purchase prices moved).
                val target = core.salesOps.foodCostTarget()
                if (target != null && (actor.role.allows(Permission.MENU_PRICE_MANAGE) || actor.role.allows(Permission.RECIPE_MANAGE))) {
                    core.branches.all().filter { actor.canAccess(Scope.Branch(it.id)) }.forEach { b ->
                        safe { core.reports.foodCosts(Scope.Branch(b.id), today) }.filter { it.costBp >= target }.forEach { r ->
                            add(Alert("FOODCOST:${b.id.value.value}:${r.item.id.value}", Alert.Kind.FOOD_COST, "بهای مواد «${r.item.name}» ${Fa.percent(r.costBp)} قیمت",
                                "هدف ${Fa.percent(target)} است: بهای مواد ${Fa.rial(r.cost)} و قیمت منو ${Fa.rial(r.price)} ریال. قیمت یا رسپی را بازبینی کنید."))
                        }
                    }
                }
                if (actor.role.allows(Permission.PURCHASE_APPROVE)) {
                    safe { core.books.pendingApprovals() }.filter { it.approvedBy.none { n -> n == actor.displayName } }.forEach { a ->
                        add(Alert("APPROVE:${a.invoice.invoice.id.value}", Alert.Kind.APPROVAL, "فاکتور در انتظار تأیید",
                            "${a.invoice.supplier} · ${Fa.rial(a.invoice.invoice.total)} ریال"))
                    }
                }
            }
        }
    }

    /** A read this role may not do (or a branch it cannot see) simply yields nothing. */
    private fun <T> safe(block: () -> List<T>): List<T> = try { block() } catch (e: DomainException) { emptyList() }

    companion object {
        const val PRICE_DAYS = 2L
        const val PRICE_THRESHOLD_BP = 1_000L
        const val CHEQUE_DAYS = 2L
    }
}
