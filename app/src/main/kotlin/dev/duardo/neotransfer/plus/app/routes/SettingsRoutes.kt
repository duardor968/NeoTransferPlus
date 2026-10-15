package dev.duardo.neotransfer.plus.app.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.LocalGate
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.platform.openNotificationSettings
import dev.duardo.neotransfer.plus.platform.openUrl
import dev.duardo.neotransfer.plus.ui.model.Appearance
import dev.duardo.neotransfer.plus.ui.model.PasswordSettingsUi
import dev.duardo.neotransfer.plus.ui.model.SettingsUi
import dev.duardo.neotransfer.plus.ui.settings.AboutActions
import dev.duardo.neotransfer.plus.ui.settings.AboutScreen
import dev.duardo.neotransfer.plus.ui.settings.LicensesScreen
import dev.duardo.neotransfer.plus.ui.settings.PasswordSettingsActions
import dev.duardo.neotransfer.plus.ui.settings.PasswordSettingsScreen
import dev.duardo.neotransfer.plus.ui.settings.SettingsActions
import dev.duardo.neotransfer.plus.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
private fun versionName(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}

@Composable
fun SettingsRoute(plus: PlusApp) {
    val context = LocalContext.current
    val account by plus.account.collectAsState()
    val saved by plus.vault.saved.collectAsState()
    val appearance by plus.preferences.appearance.collectAsState()
    var notifications by remember { mutableStateOf(plus.notifier.enabled()) }
    var confirming by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notifications = plus.notifier.enabled()
        onPauseOrDispose { }
    }
    val actions = object : SettingsActions {
        override fun back() { plus.nav.back() }
        override fun signOut() { confirming = true }
        override fun confirmSignOut() {
            confirming = false
            signingOut = true
            plus.scope.launch {
                if (!plus.signOut()) plus.say("No se pudo borrar la sesión de este teléfono.")
                signingOut = false
            }
        }
        override fun cancelSignOut() { confirming = false }
        override fun password() = plus.nav.push(Dest.PasswordSettings)
        override fun appearance(value: Appearance) = plus.preferences.appearance(value)
        override fun notifications() = context.openNotificationSettings()
        override fun about() = plus.nav.push(Dest.About)
    }
    SettingsScreen(
        SettingsUi(account?.toUi(), saved, appearance, notifications, versionName(), confirmingSignOut = confirming, signingOut = signingOut),
        actions,
    )
}

@Composable
fun PasswordSettingsRoute(plus: PlusApp) {
    val gate = LocalGate.current
    val saved by plus.vault.saved.collectAsState()
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val actions = object : PasswordSettingsActions {
        override fun back() { plus.nav.back() }
        override fun value(value: String) { error = null; typed = value }
        override fun save() {
            if (!gate.canPay()) return run { error = "Registra una huella en el teléfono para guardarla." }
            val password = typed.toCharArray()
            val cipher = runCatching { plus.vault.encryptCipher() }.getOrNull() ?: return run {
                password.fill('\u0000')
                error = "No se pudo proteger la contraseña con la huella."
            }
            gate.authenticate(
                cipher, "Guardar la contraseña de pago", null,
                onSuccess = { authorized ->
                    val stored = runCatching { plus.vault.save(authorized, password) }.isSuccess
                    password.fill('\u0000')
                    if (stored) { typed = ""; plus.say("Contraseña de pago guardada"); plus.nav.back() }
                    else error = "No se pudo guardar la contraseña."
                },
                onCancelled = { password.fill('\u0000') },
                onError = { message -> password.fill('\u0000'); error = message },
            )
        }
        override fun remove() { confirming = true }
        override fun confirmRemove() {
            confirming = false
            runCatching { plus.vault.clear() }
        }
        override fun cancelRemove() { confirming = false }
    }
    PasswordSettingsScreen(PasswordSettingsUi(saved, typed, error, confirming), actions)
}

@Composable
fun AboutRoute(plus: PlusApp) {
    val context = LocalContext.current
    val actions = object : AboutActions {
        override fun back() { plus.nav.back() }
        override fun source() = context.openUrl("https://github.com/duardor968/NeoTransferPlus")
        override fun licenses() = plus.nav.push(Dest.Licenses)
    }
    AboutScreen(versionName(), actions)
}

@Composable
fun LicensesRoute(plus: PlusApp) {
    val context = LocalContext.current
    val licenses = remember {
        listOf("Inter" to "Inter-OFL.txt", "Arimo" to "Arimo-OFL.txt", "Material Symbols" to "Material-Icons-LICENSE.txt").map { (name, file) ->
            name to context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
        }
    }
    LicensesScreen(licenses, { plus.nav.back() })
}
