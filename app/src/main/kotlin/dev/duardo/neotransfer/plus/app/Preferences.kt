package dev.duardo.neotransfer.plus.app

import android.content.Context
import dev.duardo.neotransfer.plus.ui.model.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small device choices: theme, and whether the first-start permission request already happened. */
class Preferences(context: Context) {
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val mutableAppearance = MutableStateFlow(
        Appearance.entries.firstOrNull { it.name == preferences.getString(APPEARANCE, null) } ?: Appearance.System,
    )
    val appearance: StateFlow<Appearance> = mutableAppearance.asStateFlow()

    fun appearance(value: Appearance) {
        preferences.edit().putString(APPEARANCE, value.name).apply()
        mutableAppearance.value = value
    }

    /** True once: the first start asks for every permission at the same time. */
    fun firstStart(): Boolean {
        if (preferences.getBoolean(PERMISSIONS_ASKED, false)) return false
        preferences.edit().putBoolean(PERMISSIONS_ASKED, true).apply()
        return true
    }

    private companion object {
        const val APPEARANCE = "appearance"
        const val PERMISSIONS_ASKED = "permissions_asked"
    }
}
