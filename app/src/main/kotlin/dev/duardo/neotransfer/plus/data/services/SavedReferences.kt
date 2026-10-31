package dev.duardo.neotransfer.plus.data.services

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the person said of the references in Guardados, by key (`ELECTRICITY:01234567891`): the name given to one, and
 * when one was taken out. The references themselves are the payments ENZONA confirmed, in the payment journal.
 */
class SavedReferences(context: Context) {
    /** [names] and the moment each one was [removed] (epoch millis), by key. */
    data class Marks(val names: Map<String, String> = emptyMap(), val removed: Map<String, Long> = emptyMap())

    private val preferences = context.getSharedPreferences("saved_references", Context.MODE_PRIVATE)
    private val mutableMarks = MutableStateFlow(read())
    val marks: StateFlow<Marks> = mutableMarks.asStateFlow()

    /** A blank [name] goes back to none. */
    fun rename(key: String, name: String) {
        val value = name.trim()
        preferences.edit { if (value.isEmpty()) remove(NAME + key) else putString(NAME + key, value) }
        mutableMarks.value = read()
    }

    /** Out of Guardados, and its name forgotten, until a payment made after [at]. */
    fun remove(key: String, at: Long) {
        preferences.edit { remove(NAME + key).putLong(REMOVED + key, at) }
        mutableMarks.value = read()
    }

    private fun read(): Marks {
        val all = preferences.all
        return Marks(
            names = all.mapNotNull { (key, value) -> if (key.startsWith(NAME) && value is String) key.removePrefix(NAME) to value else null }.toMap(),
            removed = all.mapNotNull { (key, value) -> if (key.startsWith(REMOVED) && value is Long) key.removePrefix(REMOVED) to value else null }.toMap(),
        )
    }

    private companion object {
        const val NAME = "name:"
        const val REMOVED = "removed:"
    }
}
