package dev.duardo.neotransfer.plus.app.routes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.duardo.neotransfer.plus.app.Banks
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.CodeEntry
import dev.duardo.neotransfer.plus.app.LocalAuthorizer
import dev.duardo.neotransfer.plus.app.NewCode
import dev.duardo.neotransfer.plus.app.PinErased
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.accountCardsUi
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.core.card.CardNumbers
import dev.duardo.neotransfer.plus.core.card.EnzonaCoordinates
import dev.duardo.neotransfer.plus.data.EnzonaCardChallenge
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.EnzonaResult
import dev.duardo.neotransfer.plus.data.cardholder
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.platform.AccessCode
import dev.duardo.neotransfer.plus.platform.CodeKey
import dev.duardo.neotransfer.plus.platform.PinLock
import dev.duardo.neotransfer.plus.platform.autostartSettings
import dev.duardo.neotransfer.plus.platform.batteryExemptionRequest
import dev.duardo.neotransfer.plus.platform.ignoresBatteryOptimizations
import dev.duardo.neotransfer.plus.platform.openAutostartSettings
import dev.duardo.neotransfer.plus.platform.openNotificationSettings
import dev.duardo.neotransfer.plus.platform.openUrl
import dev.duardo.neotransfer.plus.ui.lock.CodeSetupActions
import dev.duardo.neotransfer.plus.ui.lock.CodeSetupScreen
import dev.duardo.neotransfer.plus.ui.model.AccessCodeUi
import dev.duardo.neotransfer.plus.ui.model.AddCardUi
import dev.duardo.neotransfer.plus.ui.model.Appearance
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.CellUi
import dev.duardo.neotransfer.plus.ui.model.CodeSetupUi
import dev.duardo.neotransfer.plus.ui.model.CodeStep
import dev.duardo.neotransfer.plus.ui.model.NotAddedKind
import dev.duardo.neotransfer.plus.ui.model.PasswordSettingsUi
import dev.duardo.neotransfer.plus.ui.model.PinProtection
import dev.duardo.neotransfer.plus.ui.model.SettingsUi
import dev.duardo.neotransfer.plus.ui.payment.isPaymentPassword
import dev.duardo.neotransfer.plus.ui.settings.AboutActions
import dev.duardo.neotransfer.plus.ui.settings.AboutScreen
import dev.duardo.neotransfer.plus.ui.settings.AccessCodeActions
import dev.duardo.neotransfer.plus.ui.settings.AccessCodeScreen
import dev.duardo.neotransfer.plus.ui.settings.AddCardActions
import dev.duardo.neotransfer.plus.ui.settings.AddCardScreen
import dev.duardo.neotransfer.plus.ui.settings.LicensesScreen
import dev.duardo.neotransfer.plus.ui.settings.PasswordSettingsActions
import dev.duardo.neotransfer.plus.ui.settings.PasswordSettingsScreen
import dev.duardo.neotransfer.plus.ui.settings.SettingsActions
import dev.duardo.neotransfer.plus.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val unverified by plus.vault.unverified.collectAsState()
    val pinLock by plus.vault.lock.collectAsState()
    val codeActive by plus.code.active.collectAsState()
    val appearance by plus.preferences.appearance.collectAsState()
    var notifications by remember { mutableStateOf(plus.notifier.enabled()) }
    var batteryExempt by remember { mutableStateOf(context.ignoresBatteryOptimizations()) }
    val autostart = remember { context.autostartSettings() != null }
    var offeringAutostart by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    val wallet by plus.wallet.collectAsState()
    var aliasCard by remember { mutableStateOf<CardUi?>(null) }
    var cardMenu by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    val cards = remember(wallet, busy) { accountCardsUi(wallet.orEmpty(), busy) }
    /** One change of [cardId] at a time; the list follows ENZONA's answer. */
    fun change(cardId: String, request: suspend () -> EnzonaResult<Unit>) {
        busy = busy + cardId
        plus.scope.launch {
            val result = request()
            busy = busy - cardId
            if (result is EnzonaResult.Failed) plus.say(result.serviceFault?.description ?: result.reason.message())
        }
    }
    LifecycleResumeEffect(Unit) {
        notifications = plus.notifier.enabled()
        batteryExempt = context.ignoresBatteryOptimizations()
        onPauseOrDispose { }
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryExempt = context.ignoresBatteryOptimizations()
        offeringAutostart = batteryExempt && autostart
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
        override fun accessCode() = plus.nav.push(Dest.AccessCode)
        override fun appearance(value: Appearance) = plus.preferences.appearance(value)
        override fun notifications() = context.openNotificationSettings()
        override fun background() {
            if (context.ignoresBatteryOptimizations()) offeringAutostart = autostart
            else if (runCatching { battery.launch(context.batteryExemptionRequest()) }.isFailure) plus.say("No se pudo abrir el ajuste de batería")
        }
        override fun openAutostart() {
            offeringAutostart = false
            if (!context.openAutostartSettings()) plus.say("No se pudo abrir el inicio automático")
        }
        override fun closeAutostart() { offeringAutostart = false }
        override fun about() = plus.nav.push(Dest.About)
        override fun addCard() = plus.nav.push(Dest.AddCard)
        override fun cardMenu(cardId: String) { cardMenu = cardId }
        override fun closeCardMenu() { cardMenu = null }
        override fun editAlias(cardId: String) {
            cardMenu = null
            aliasCard = cards.firstOrNull { it.card.id == cardId }?.card
        }
        override fun makeDefault(cardId: String) {
            cardMenu = null
            change(cardId) { plus.repository.setDefaultCard(cardId) }
        }
        override fun removeCard(cardId: String) {
            cardMenu = null
            removing = cardId
        }
        override fun confirmRemoveCard() {
            val cardId = removing ?: return
            removing = null
            change(cardId) { plus.repository.removeCard(cardId) }
        }
        override fun cancelRemoveCard() { removing = null }
    }
    aliasCard?.let { CardAliasEditor(plus, it) { aliasCard = null } }
    SettingsScreen(
        SettingsUi(
            account?.toUi(), saved, appearance, notifications, versionName(), confirmingSignOut = confirming, signingOut = signingOut,
            batteryExempt = batteryExempt, autostart = autostart, offeringAutostart = offeringAutostart,
            cards = cards, cardMenu = cardMenu, removing = cards.firstOrNull { it.card.id == removing }?.card,
            passwordUnverified = unverified, passwordProtection = pinLock.protection(), codeActive = codeActive,
        ),
        actions,
    )
}

private fun cellLabels(cells: EnzonaCoordinates): List<String> =
    listOf("Coordenada ${cells.c1}", "Coordenada ${cells.c2}", "Posición ${cells.p1} del PIN", "Posición ${cells.p2} del PIN")

/**
 * How a send that did not add the card reads: ENZONA's no with its own reason, a generic one only when it gave none;
 * without ENZONA's answer the card may be in the account.
 */
internal fun EnzonaResult.Failed.notAdded(): AddCardUi.NotAdded = when (reason) {
    EnzonaFailure.TIMEOUT, EnzonaFailure.NETWORK ->
        AddCardUi.NotAdded(NotAddedKind.Unconfirmed, "${reason.message()} Mira en Ajustes si la tarjeta aparece antes de intentarlo de nuevo.")
    EnzonaFailure.REJECTED ->
        AddCardUi.NotAdded(NotAddedKind.Refused, serviceFault?.description?.takeIf(String::isNotBlank)?.asSentence() ?: "ENZONA no dio el motivo.")
    else -> AddCardUi.NotAdded(NotAddedKind.Failed, reason.message())
}

/** A reason as a sentence of its own: «tarjeta vencida» → «Tarjeta vencida.». */
private fun String.asSentence(): String = trim().replaceFirstChar(Char::uppercase).let { if (it.last() in ".!?…") it else "$it." }

/**
 * Ajustes › Tarjetas › Añadir tarjeta. The cells of the attempt and what is typed for them live in this screen's
 * memory only, never in saved state: they are dropped after the send, when going back and when leaving.
 */
@Composable
fun AddCardRoute(plus: PlusApp) {
    val account by plus.account.collectAsState()
    val wallet by plus.wallet.collectAsState()
    var number by rememberSaveable { mutableStateOf("") }
    var expiry by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var unlisted by remember { mutableStateOf<String?>(null) }
    var challenge by remember { mutableStateOf<EnzonaCardChallenge?>(null) }
    val typed = remember { mutableStateListOf<String>() }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<AddCardUi?>(null) }
    fun forget() {
        challenge = null
        typed.clear()
    }
    DisposableEffect(Unit) { onDispose { forget() } }

    val issuer = CardNumbers.issuer(number)
    val bank = issuer.bank?.let(Banks::shortName)
    val card = CardUi(
        "new", bank.orEmpty(), CardArt.of(bank), number.ifEmpty { null }, number.takeIf { it.length == 16 }?.takeLast(4),
        issuer.currency.orEmpty(), account?.cardholder?.ifEmpty { null }, expiry.takeIf { it.length == 4 }?.let { "${it.take(2)}/${it.drop(2)}" },
    )
    val numberError = when {
        number.length < 16 -> null
        !luhn(number) -> "Algún dígito está mal"
        issuer.bank == null -> "Solo tarjetas de BPA, BANDEC o Metropolitano"
        wallet.orEmpty().any { it.pan == number } -> "Ya está en tu cuenta"
        else -> unlisted
    }
    val expiryError = if (expiry.length == 4 && expiry.take(2).toInt() !in 1..12) "Mes no válido" else null
    val canContinue = number.length == 16 && numberError == null && expiry.length == 4 && expiryError == null
    val actions = object : AddCardActions {
        override fun back() {
            when {
                sending -> Unit
                challenge != null -> forget()
                else -> plus.nav.back()
            }
        }
        override fun number(value: String) {
            number = value.filter(Char::isDigit).take(16)
            unlisted = null
        }
        override fun expiry(value: String) { expiry = value.filter(Char::isDigit).take(4) }
        override fun scan() { scanning = true }
        override fun next() {
            val cardBank = issuer.bank
            if (!canContinue || checking || cardBank == null) return
            checking = true
            plus.scope.launch {
                when (val result = plus.repository.cardChallenge(cardBank)) {
                    is EnzonaResult.Ok -> when (val found = result.value) {
                        null -> unlisted = "ENZONA no admite tarjetas nuevas de$NoBreak${Banks.shortName(cardBank)}"
                        else -> {
                            typed.clear()
                            repeat(found.coordinates.labels.size) { typed += "" }
                            challenge = found
                        }
                    }
                    is EnzonaResult.Failed -> plus.say(result.reason.message())
                }
                checking = false
            }
        }
        override fun cell(index: Int, value: String) {
            if (!sending && index in typed.indices) typed[index] = value.filter(Char::isDigit)
        }
        override fun send() {
            val current = challenge
            if (current == null || sending || typed.any(String::isEmpty)) return
            sending = true
            val sent = card
            val answers = typed.map(String::toCharArray)
            plus.scope.launch {
                val result = plus.repository.addCard(number, expiry, current, answers)
                forget()
                sending = false
                outcome = when (result) {
                    is EnzonaResult.Ok -> AddCardUi.Added(sent)
                    is EnzonaResult.Failed -> result.notAdded().also { if (it.kind == NotAddedKind.Unconfirmed) plus.refresh() }
                }
            }
        }
        override fun review() { outcome = null }
        override fun done() { plus.nav.back() }
    }
    BackHandler(sending || challenge != null) { actions.back() }
    val state = outcome ?: if (sending) AddCardUi.Sending(card) else challenge?.let { found ->
        AddCardUi.Cells(card, cellLabels(found.coordinates).zip(typed, ::CellUi), canSend = typed.none(String::isEmpty))
    } ?: AddCardUi.Form(card, number, expiry, numberError, expiryError, canContinue, checking)
    AddCardScreen(state, actions)
    if (scanning && state is AddCardUi.Form) CardScanner(phones = false, onClose = { scanning = false }) { read, _ -> read?.let(actions::number) }
}

/** What keeps a payment PIN, as the screens say it. */
internal fun PinLock?.protection(): PinProtection = when (this) {
    PinLock.Fingerprint -> PinProtection.Fingerprint
    PinLock.ScreenLock -> PinProtection.ScreenLock
    PinLock.Code -> PinProtection.Code
    null -> PinProtection.None
}

@Composable
fun PasswordSettingsRoute(plus: PlusApp) {
    val authorizer = LocalAuthorizer.current
    val saved by plus.vault.saved.collectAsState()
    val pinLock by plus.vault.lock.collectAsState()
    val protectsPin by plus.code.protectsPin.collectAsState()
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val actions = object : PasswordSettingsActions {
        override fun back() { plus.nav.back() }
        override fun value(value: String) { error = null; typed = value }
        override fun save() {
            if (!isPaymentPassword(typed)) return
            authorizer.save(
                typed.toCharArray(),
                onSaved = { typed = ""; plus.say("PIN de pago guardado"); plus.nav.back() },
                onProblem = { message -> error = message },
            )
        }
        override fun remove() { confirming = true }
        override fun confirmRemove() {
            confirming = false
            if (runCatching { plus.vault.clear() }.isFailure) plus.say("No se pudo borrar el PIN.")
        }
        override fun cancelRemove() { confirming = false }
    }
    PasswordSettingsScreen(PasswordSettingsUi(saved, typed, error, confirming, plus.vault.lockFor(AccessCode.underCode(pinLock, protectsPin)).protection()), actions)
}

/**
 * Ajustes › Código de acceso: without a code, the new one typed twice; with one, what it does. Changing it asks the
 * current one first; turning it off or changing what it protects asks it in a sheet.
 */
@Composable
fun AccessCodeRoute(plus: PlusApp) {
    val authorizer = LocalAuthorizer.current
    val scope = rememberCoroutineScope()
    val active by plus.code.active.collectAsState()
    val pinLock by plus.vault.lock.collectAsState()
    val protectsPin by plus.code.protectsPin.collectAsState()
    /** The current code, typed before a change. */
    var current by remember { mutableStateOf<CodeEntry?>(null) }
    /** A new code: the first one straight away, or after the current one when it changes. */
    var newCode by remember { mutableStateOf(NewCode().takeIf { !plus.code.active.value }) }
    /** The current code's key while it changes, to move the PIN it protects to the new one. */
    var old by remember { mutableStateOf<CodeKey?>(null) }
    fun create(typed: CharArray) {
        val previous = old
        scope.launch {
            val created = runCatching {
                withContext(Dispatchers.Default) {
                    plus.code.create(typed) { key ->
                        // A PIN the old code does not open throws: the old code stays, and so does the PIN.
                        if (previous != null && plus.vault.lock.value == PinLock.Code) {
                            val password = plus.vault.openWithCode(previous)
                            try { plus.vault.reseal(plus.vault.sealWithCode(key, password)) } finally { password.fill('\u0000') }
                        }
                    }
                }
            }.isSuccess
            typed.fill('\u0000')
            newCode = null
            old = null
            if (!created) {
                plus.say("No se pudo guardar el código")
                if (previous == null) plus.nav.back()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { current?.close(); newCode?.close() } }
    val setup = newCode
    val typing = current
    when {
        setup != null -> {
            fun leave() {
                if (!active) plus.nav.back()
                setup.close()
                newCode = null
                old = null
            }
            BackHandler { leave() }
            CodeSetupScreen(CodeSetupUi(setup.step, setup.ui), object : CodeSetupActions {
                override fun key(key: KeypadKey) = setup.key(key, ::create)
                override fun back() = leave()
            })
        }
        typing != null -> {
            fun leave() {
                typing.close()
                current = null
            }
            BackHandler { leave() }
            CodeSetupScreen(CodeSetupUi(CodeStep.Current, typing.ui), object : CodeSetupActions {
                override fun key(key: KeypadKey) = typing.key(key)
                override fun back() = leave()
            })
        }
        active -> AccessCodeScreen(AccessCodeUi(
            AccessCode.underCode(pinLock, protectsPin), fingerprint = plus.vault.lockFor(codeProtects = false) == PinLock.Fingerprint,
        ), object : AccessCodeActions {
            override fun back() { plus.nav.back() }
            override fun change() {
                current = CodeEntry(plus.code, scope, Dispatchers.Default) { key ->
                    current?.close()
                    current = null
                    old = key
                    newCode = NewCode()
                }
            }
            override fun protectPin(on: Boolean) {
                val title = "Usar también para el PIN de pago"
                authorizer.askCode(title, null) { key ->
                    if (on) authorizer.moveToCode(key, title, onDone = { plus.code.protectPin(true) }, onProblem = plus::say)
                    else authorizer.moveFromCode(key, title, onDone = { erased ->
                        plus.code.protectPin(false)
                        if (erased) plus.say(PinErased)
                    }, onProblem = plus::say)
                }
            }
            override fun disable() {
                val title = "Desactivar código"
                authorizer.askCode("¿Desactivar el código?", null) { key ->
                    authorizer.moveFromCode(key, title, onDone = { erased ->
                        plus.code.remove()
                        if (erased) plus.say(PinErased)
                        plus.nav.back()
                    }, onProblem = plus::say)
                }
            }
        })
        // Leaving before a first code was set.
        else -> Unit
    }
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
