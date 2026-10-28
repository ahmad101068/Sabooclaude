package ir.sabou.core

import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Scope
import ir.sabou.platform.Command
import ir.sabou.platform.Permission
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Audit 2026-10-28, SABOU-AUDIT-001: a command from the second phone arrives as a deserialized object, so whatever
 * decides who may run it must come from the code, never from a field the sender filled in. No command class may
 * store its required permission, extra permissions or shared-catalog flag; each is a getter.
 */
class CommandTrustTest {
    private val guarded = listOf("requiredPermission", "additionalPermissions", "sharedCatalog")

    private fun commandClasses(): List<Class<*>> {
        val names = HashSet<String>()
        // Production classes only: the test sources' own sample commands are not shipped.
        System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
            .filter { !it.name.contains("-test") && !it.path.replace(File.separatorChar, '/').contains("/test") }.forEach { entry ->
            if (entry.isDirectory) entry.walkTopDown().filter { it.name.endsWith(".class") }
                .forEach { names += it.relativeTo(entry).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            else if (entry.name.endsWith(".jar") && entry.exists()) JarFile(entry).use { jar ->
                jar.entries().asSequence().map { it.name }.filter { it.startsWith("ir/sabou/") && it.endsWith(".class") }
                    .forEach { names += it.removeSuffix(".class").replace('/', '.') }
            }
        }
        return names.filter { it.startsWith("ir.sabou.") && !it.contains("Test") }
            .mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
            .filter { Command::class.java.isAssignableFrom(it) && !it.isInterface && !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
    }

    @Test fun noCommandStoresWhoMayRunIt() {
        val commands = commandClasses()
        assertTrue(commands.size > 150, "found only ${commands.size} command classes")
        val stored = commands.flatMap { c -> guarded.filter { f -> runCatching { c.getDeclaredField(f) }.isSuccess }.map { "${c.name}.$it" } }
        if (stored.isNotEmpty()) fail("commands that store a trust field (make it a getter): $stored")
    }

    @Test fun aForgedPermissionDoesNotSurviveDeserialization() {
        val payment = ir.sabou.treasury.RecordPayment(GlobalId.new(), Scope.Organization, GlobalId.new(), ir.sabou.treasury.PaymentPurpose.OWNER_WITHDRAWAL,
            Money.of(4_000_000), BusinessDate(20_000), "x")
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(payment) } }.toByteArray()
        val back = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as Command
        assertEquals(Permission.TREASURY_PAYMENT, back.requiredPermission)
        // The serialized form carries no such field at all, so there is nothing a sender could change.
        assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("requiredPermission"))
    }
}
