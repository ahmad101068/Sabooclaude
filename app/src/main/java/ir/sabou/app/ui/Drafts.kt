package ir.sabou.app.ui

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.Size
import android.util.SizeF
import android.util.SparseArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.toMutableStateMap
import java.io.Serializable

/**
 * Unfinished forms survive the system killing the app in the background (ADR-0010).
 *
 * Every page on screen gets its own SaveableStateRegistry; form fields use `rememberSaveable`. When the app
 * goes to the background the page's values, the back stack and the branch are written — encrypted with a
 * device key — next to the database. After the same user signs in again (PIN), they come back exactly on the
 * page they left. PINs and backup passwords are never `rememberSaveable`, so they are never written.
 */
object Drafts {
    /** Drafts older than this are discarded: an abandoned form should not reappear days later. */
    const val MAX_AGE_MILLIS = 12L * 60 * 60 * 1000

    private val bundleTypes = arrayOf(
        Serializable::class.java, Parcelable::class.java, String::class.java, SparseArray::class.java,
        Binder::class.java, Size::class.java, SizeF::class.java,
    )

    /** The same rule Compose uses for the activity's Bundle (values that a Parcel can carry). */
    fun canBeSaved(value: Any): Boolean {
        if (value is SnapshotMutableState<*>) {
            val p = value.policy
            if (p !== neverEqualPolicy<Any?>() && p !== structuralEqualityPolicy<Any?>() && p !== referentialEqualityPolicy<Any?>()) return false
            return value.value?.let(::canBeSaved) ?: true
        }
        if (value is Function<*> && value is Serializable) return false   // lambdas would not restore
        return bundleTypes.any { it.isInstance(value) }
    }

    fun toBundle(values: Map<String, List<Any?>>): Bundle = Bundle().apply {
        values.forEach { (key, list) ->
            @Suppress("UNCHECKED_CAST")
            putParcelableArrayList(key, ArrayList(list) as ArrayList<Parcelable?>)
        }
    }

    fun fromBundle(bundle: Bundle): Map<String, List<Any?>> = bundle.keySet().associateWith { key ->
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (bundle.get(key) as List<Any?>)
    }

    fun marshall(bundle: Bundle): ByteArray {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun unmarshall(bytes: ByteArray, loader: ClassLoader): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(loader)).also { it.classLoader = loader }
        } finally {
            parcel.recycle()
        }
    }
}

/** A list of form rows, kept across process death. Each row is saved as a list of its field values. */
fun <R> rowsSaver(fields: (R) -> List<Any?>, row: (List<Any?>) -> R): Saver<SnapshotStateList<R>, Any> = listSaver(
    save = { rows -> rows.map { ArrayList(fields(it)) } },
    restore = { saved -> runCatching { saved.map { @Suppress("UNCHECKED_CAST") row(it as List<Any?>) }.toMutableStateList() }.getOrNull() },
)

@Composable
fun <R> rememberRows(fields: (R) -> List<Any?>, row: (List<Any?>) -> R, init: () -> List<R>): SnapshotStateList<R> =
    rememberSaveable(saver = rowsSaver(fields, row)) { init().toMutableStateList() }

/** A map of field values (e.g. counted quantity per item), kept across process death. */
@Composable
fun <K, V> rememberValueMap(): SnapshotStateMap<K, V> = rememberSaveable(
    saver = listSaver<SnapshotStateMap<K, V>, Any?>(
        save = { m -> m.entries.flatMap { listOf(it.key, it.value) } },
        restore = { flat ->
            @Suppress("UNCHECKED_CAST")
            runCatching { flat.chunked(2).map { (k, v) -> (k as K) to (v as V) }.toMutableStateMap() }.getOrNull()
        },
    ),
) { androidx.compose.runtime.mutableStateMapOf() }

/** A plain list of values (e.g. chosen branches), kept across process death. */
@Composable
fun <T> rememberValueList(init: () -> List<T>): SnapshotStateList<T> = rememberSaveable(
    saver = listSaver<SnapshotStateList<T>, Any?>(save = { ArrayList(it) }, restore = { @Suppress("UNCHECKED_CAST") (it as List<T>).toMutableStateList() }),
) { init().toMutableStateList() }
