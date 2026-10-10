package ir.sabou.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Architecture rule: Rial is the one money unit of the product. Amounts are stored, typed, shown, printed and
 * exported in Rial; no screen, report or export may convert to Toman. This scans the production sources so a
 * Toman label or a ÷10 on an amount cannot come back unnoticed.
 */
class MoneyUnitRuleTest {
    private val forbidden = listOf(
        Regex("تومان") to "Toman label",
        Regex("(?i)\\btoman") to "Toman identifier",
        Regex("rial\\s*/\\s*10\\b") to "Rial ÷ 10",
        Regex("rial\\s*\\*\\s*10\\b") to "Rial × 10",
        Regex("rial\\)\\.movePointLeft\\(") to "decimal shift of an amount",
    )

    @Test fun noScreenReportOrExportConvertsMoneyToToman() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val sources = listOf("app/src/main", "modules").map { File(root, it) }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") && "/src/main/" in it.path && "/build/" !in it.path }.toList() }
        assertTrue(sources.size > 100, "found only ${sources.size} source files")
        val hits = sources.flatMap { f ->
            f.readLines().withIndex().flatMap { (i, line) ->
                forbidden.filter { (re, _) -> re.containsMatchIn(line) }.map { (_, why) -> "${f.relativeTo(root)}:${i + 1}: $why" }
            }
        }
        assertEquals(emptyList(), hits)
    }
}
