package dev.duardo.neotransfer.plus.data.enzona

import android.content.Context

/**
 * The account's email, offered to ENZONA's sign-in page as `login_hint`. Saved when a sign-in reads the account, kept
 * when ENZONA ends the session, forgotten on sign-out. Not a credential: the password and the SMS stay on ENZONA's page.
 */
internal class EnzonaLoginHint(context: Context) {
    private val preferences = context.getSharedPreferences("enzona_login", Context.MODE_PRIVATE)

    fun read(): String? = preferences.getString(KEY, null)

    fun save(email: String?) {
        val value = email?.trim()?.takeIf { it.isNotEmpty() }
        preferences.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
    }

    private companion object {
        const val KEY = "email"
    }
}
