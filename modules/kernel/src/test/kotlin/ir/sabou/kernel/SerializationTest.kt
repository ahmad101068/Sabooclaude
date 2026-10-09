package ir.sabou.kernel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unfinished forms are kept across process death (the app saves them with Java serialization). */
class SerializationTest {
    private fun roundTrip(value: Any?): Any? {
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(value) } }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }

    @Test fun valueTypesSurviveBoxedAsTheyAreInFormState() {
        val id = GlobalId.new()
        val values: List<Any?> = listOf(
            Money.of(12_345), Quantity.of(1_500_000), BusinessDate(20_000), id, BranchId(id), Scope.Branch(BranchId(id)), Scope.Organization, null,
        )
        values.forEach { assertEquals(it, roundTrip(it)) }
        assertEquals(Scope.Organization, roundTrip(Scope.Organization))   // the object stays a singleton
        assertEquals(values, roundTrip(ArrayList(values)))
    }
}
