package dev.duardo.neotransfer.plus.app.routes

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.duardo.neotransfer.plus.app.ActivityQuery
import dev.duardo.neotransfer.plus.app.Banks
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.contactMovements
import dev.duardo.neotransfer.plus.app.people
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.core.card.CardNumbers
import dev.duardo.neotransfer.plus.core.phone.Phones
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactCard
import dev.duardo.neotransfer.plus.data.contacts.Directory
import dev.duardo.neotransfer.plus.platform.copyText
import dev.duardo.neotransfer.plus.platform.cubanMobile
import dev.duardo.neotransfer.plus.platform.editInContacts
import dev.duardo.neotransfer.plus.platform.openAppSettings
import dev.duardo.neotransfer.plus.scanner.QrVerdict
import dev.duardo.neotransfer.plus.scanner.ScanMode
import dev.duardo.neotransfer.plus.scanner.ScanResult
import dev.duardo.neotransfer.plus.scanner.ScannerView
import dev.duardo.neotransfer.plus.ui.contacts.AllContactsActions
import dev.duardo.neotransfer.plus.ui.contacts.AllContactsScreen
import dev.duardo.neotransfer.plus.ui.contacts.CardEditActions
import dev.duardo.neotransfer.plus.ui.contacts.CardEditScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactEditActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactsActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.plus.ui.model.AllContactsUi
import dev.duardo.neotransfer.plus.ui.model.CardEditUi
import dev.duardo.neotransfer.plus.ui.model.CardFieldUi
import dev.duardo.neotransfer.plus.ui.model.ContactEditUi
import dev.duardo.neotransfer.plus.ui.model.ContactsContent
import dev.duardo.neotransfer.plus.ui.model.ContactsUi
import dev.duardo.neotransfer.plus.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.shell.Tab
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

private val marks = Regex("""\p{Mn}+""")

/** Accent- and case-insensitive text. */
private fun normalized(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(marks, "")

/** The name as typed, without minding accents or case. */
fun Contact.matchesName(query: String): Boolean = normalized(query).trim().let { it.isEmpty() || it in normalized(name) }

/** The name, or three or more digits of a card number or a phone. */
fun Contact.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val digits = query.filter(Char::isDigit)
    return matchesName(query) || (digits.length >= 3 && (cards.any { digits in it.pan } || phones.any { digits in it }))
}

/** The money tied to each contact ([contactMovements]), from what the app has read. */
@Composable
private fun rememberContactMovements(plus: PlusApp): Map<String, List<OperationUi>> {
    val contacts by plus.contactList.collectAsState()
    val journal by plus.journal.collectAsState()
    val transactions by plus.transactions.collectAsState()
    val account by plus.account.collectAsState()
    val cards = rememberWallet(plus).map { it.card }
    return remember(contacts, journal, transactions, account, cards) {
        contactMovements(journal.orEmpty(), transactions.orEmpty(), account?.username, cards, contacts.orEmpty(), plus.zone)
    }
}

@Composable
fun ContactsRoute(plus: PlusApp) {
    val context = LocalContext.current
    val contacts by plus.contactList.collectAsState()
    val addressBook by plus.addressBook.collectAsState()
    val movements = rememberContactMovements(plus)
    var query by rememberSaveable { mutableStateOf("") }
    // Refused for good, the system no longer asks: the app's settings page is where it is given.
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) plus.readAddressBook() else context.openAppSettings()
    }
    val actions = remember(plus) {
        object : ContactsActions {
            override fun query(value: String) { query = value }
            override fun open(id: String) = plus.nav.push(Dest.ContactDetail(id))
            override fun create() = plus.nav.push(Dest.ContactEdit(null))
            override fun everyone() = plus.nav.push(Dest.AllContacts)
            override fun allowContacts() = allow.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    // Read once the app comes to the front; until then there is nothing to show yet.
    val all = contacts ?: return
    val everyone = all.map { it.toUi(addressBook, movements[it.id].orEmpty()) }
    val people = everyone.people()
    val content = if (query.isBlank()) ContactsContent.People(people) else {
        val matching = all.filter { it.matches(query) }.mapTo(hashSetOf()) { it.id }
        val found = people.filter { it.id in matching }
        val shown = found.mapTo(hashSetOf()) { it.id }
        val others = everyone.filter { it.id in matching && it.id !in shown }
        if (found.isEmpty() && others.isEmpty()) ContactsContent.NoResults else ContactsContent.Results(found, others)
    }
    ContactsScreen(ContactsUi(query, content, all.size, addressBook != null), actions)
}

@Composable
fun AllContactsRoute(plus: PlusApp) {
    val contacts by plus.contactList.collectAsState()
    val addressBook by plus.addressBook.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val actions = remember(plus) {
        object : AllContactsActions {
            override fun back() { plus.nav.back() }
            override fun query(value: String) { query = value }
            override fun open(id: String) = plus.nav.push(Dest.ContactDetail(id))
        }
    }
    AllContactsScreen(AllContactsUi(query, contacts.orEmpty().filter { it.matches(query) }.map { it.toUi(addressBook) }), actions)
}

@Composable
fun ContactRoute(plus: PlusApp, id: String) {
    val context = LocalContext.current
    val contacts by plus.contactList.collectAsState()
    val addressBook by plus.addressBook.collectAsState()
    val movements = rememberContactMovements(plus)[id].orEmpty()
    val contact = contacts?.firstOrNull { it.id == id }
    var confirming by remember { mutableStateOf(false) }
    // Deleted (here or meanwhile): the detail closes once the agenda is loaded.
    val gone = contact == null && contacts != null
    LaunchedEffect(gone) { if (gone) plus.nav.back() }
    if (contact == null) return
    val ui = contact.toUi(addressBook, movements)
    val actions = object : ContactActions {
        override fun back() { plus.nav.back() }
        override fun edit() = plus.nav.push(Dest.ContactEdit(id))
        override fun addCard() = plus.nav.push(Dest.CardEdit(id))
        override fun editCard(cardId: String) = plus.nav.push(Dest.CardEdit(id, cardId))
        /** At once; the message undoes it. */
        override fun removeCard(cardId: String) {
            plus.scope.launch {
                runCatching { plus.contacts.save(contact.withoutCard(cardId)) }
                    .onSuccess { plus.say("Tarjeta quitada", "Deshacer") { plus.scope.launch { runCatching { plus.contacts.save(contact) } } } }
                    .onFailure { plus.say("No se pudo quitar la tarjeta") }
            }
        }
        override fun editInContacts() {
            val key = Directory.key(id) ?: return
            runCatching { context.editInContacts(key) }.onFailure { plus.say("No se pudo abrir Contactos") }
        }
        override fun transfer(cardId: String) {
            val card = ui.cards.firstOrNull { it.id == cardId } ?: return
            plus.nav.push(Dest.Amount(RecipientUi.Card(card, ui.name, ui.id)))
        }
        override fun openMovement(id: String) = plus.nav.push(Dest.Operation(id))
        override fun allMovements() {
            plus.activity.value = ActivityQuery(query = ui.name)
            plus.nav.home(Tab.Activity)
        }
        override fun copy(value: String) = context.copyText("Número", value)
        override fun delete() { confirming = true }
        override fun confirmDelete() {
            confirming = false
            plus.scope.launch { plus.contacts.delete(id) }
        }
        override fun cancelDelete() { confirming = false }
    }
    ContactDetailScreen(ui, actions, confirmingDelete = confirming, movements = movements.take(3))
}

/** What a typed card number tells, and what is wrong with it: a bad check digit, or a card [taken] already. */
fun cardField(number: String, label: String, taken: Collection<String> = emptyList()): CardFieldUi {
    val issuer = CardNumbers.issuer(number)
    val error = when {
        number.length < 16 -> null
        !luhn(number) -> "Algún dígito está mal"
        number in taken -> "Ya está guardada"
        else -> null
    }
    return CardFieldUi(number, label, issuer.bank?.let { Banks.shortName(it) }, issuer.currency, error)
}

/** This contact with a new card ([cardId] null) or with its saved card [cardId] changed, in the same place. */
fun Contact.withCard(cardId: String?, pan: String, label: String): Contact {
    val card = ContactCard(cardId ?: UUID.randomUUID().toString(), pan, label.trim().ifEmpty { null })
    return copy(cards = if (cardId == null) cards + card else cards.map { if (it.id == cardId) card else it })
}

fun Contact.withoutCard(cardId: String): Contact = copy(cards = cards.filterNot { it.id == cardId })

/** The camera for a card: its printed number or a Transfermóvil card code; with [phones], a printed mobile too. */
@Composable
internal fun CardScanner(phones: Boolean, onClose: () -> Unit, onRead: (card: String?, phone: String?) -> Unit) {
    BackHandler(onBack = onClose)
    ScannerView(
        mode = ScanMode.ANY,
        classifyQr = { raw -> if (cardFromQr(raw) != null) QrVerdict.Accepted else QrVerdict.Rejected("Este QR no es de una tarjeta") },
        onResult = { result ->
            onClose()
            when (result) {
                is ScanResult.CardNumber -> onRead(result.digits, null)
                is ScanResult.PhoneNumber -> if (phones) onRead(null, result.digits)
                is ScanResult.Qr -> cardFromQr(result.raw)?.let { onRead(it, null) }
            }
        },
        onClose = onClose,
    )
}

/** One card of a contact: a new one, or the saved [Dest.CardEdit.cardId]; with [Dest.CardEdit.thenTransfer] it goes on to the amount. */
@Composable
fun CardEditRoute(plus: PlusApp, dest: Dest.CardEdit) {
    val contacts by plus.contactList.collectAsState()
    val contact = contacts?.firstOrNull { it.id == dest.contactId }
    val saved = remember { plus.contactList.value?.firstOrNull { it.id == dest.contactId }?.cards?.firstOrNull { it.id == dest.cardId } }
    var number by rememberSaveable { mutableStateOf(saved?.pan.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(saved?.label.orEmpty()) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val gone = contact == null && contacts != null
    LaunchedEffect(gone) { if (gone) plus.nav.back() }
    if (contact == null) return
    val field = cardField(number, label, taken = contact.cards.filter { it.id != dest.cardId }.map { it.pan })
    val canSave = number.length == 16 && field.error == null
    val actions = object : CardEditActions {
        override fun back() { plus.nav.back() }
        override fun number(value: String) { number = value.filter(Char::isDigit).take(16) }
        override fun label(value: String) { label = value.take(30) }
        override fun scan() { scanning = true }
        override fun save() {
            if (!canSave) return
            val updated = contact.withCard(dest.cardId, number, label)
            plus.scope.launch {
                runCatching { plus.contacts.save(updated) }
                    .onSuccess {
                        val card = updated.cards.last()
                        if (dest.thenTransfer) plus.nav.replace(Dest.Amount(RecipientUi.Card(Banks.foreignCard(card.id, card.pan, card.label), updated.name, updated.id)))
                        else plus.nav.back()
                    }
                    .onFailure { plus.say("No se pudo guardar la tarjeta") }
            }
        }
    }
    CardEditScreen(CardEditUi(contact.name, dest.cardId != null, field, canSave), actions)
    if (scanning) CardScanner(phones = false, onClose = { scanning = false }) { card, _ -> card?.let { number = it } }
}

/** A contact kept only in the app, saved across rotation as plain strings: name, its first card, phones. */
private data class Draft(val name: String, val cardNumber: String, val cardLabel: String, val phones: List<Pair<String, String>>)

private val DraftSaver = listSaver<Draft, String>(
    save = { draft -> listOf(draft.name, draft.cardNumber, draft.cardLabel) + draft.phones.flatMap { listOf(it.first, it.second) } },
    restore = { values -> Draft(values[0], values[1], values[2], values.drop(3).chunked(2).map { it[0] to it[1] }) },
)

private fun phoneError(digits: String): String? = when {
    digits.isEmpty() -> null
    digits.length < 8 -> "Número incompleto"
    cubanMobile(digits) == null -> "No es un móvil cubano"
    else -> null
}

/**
 * A contact kept only in the app: new ([Dest.ContactEdit.id] null), with its first card and what is already known
 * ([Dest.ContactEdit.prefill]: a scanned number, a paid card); or its name and phones. Its cards are managed on its page.
 */
@Composable
fun ContactEditRoute(plus: PlusApp, dest: Dest.ContactEdit) {
    val contacts by plus.contactList.collectAsState()
    val existing = dest.id?.let { id -> contacts?.firstOrNull { it.id == id } }
    val start = existing ?: dest.prefill
    var draft by rememberSaveable(start?.id, stateSaver = DraftSaver) {
        val card = dest.prefill?.cards?.firstOrNull()
        mutableStateOf(
            Draft(
                start?.name.orEmpty(), card?.pan.orEmpty(), card?.label.orEmpty(),
                start?.phones.orEmpty().ifEmpty { listOf("") }.map { UUID.randomUUID().toString() to it },
            ),
        )
    }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val card = if (dest.id == null) cardField(draft.cardNumber, draft.cardLabel) else null
    val phones = draft.phones.map { (id, number) -> EditablePhoneUi(id, number, phoneError(number)) }
    val canSave = draft.name.isNotBlank() && (card == null || card.error == null && draft.cardNumber.length in setOf(0, 16)) &&
        draft.phones.all { it.second.isEmpty() || phoneError(it.second) == null }
    val actions = object : ContactEditActions {
        override fun back() { plus.nav.back() }
        override fun save() {
            if (!canSave) return
            val numbers = draft.phones.map { it.second }.filter(String::isNotEmpty).distinct()
            val contact = existing?.copy(name = draft.name.trim(), phones = numbers) ?: Contact(
                UUID.randomUUID().toString(), draft.name.trim(),
                listOfNotNull(draft.cardNumber.ifEmpty { null }?.let { ContactCard(UUID.randomUUID().toString(), it, draft.cardLabel.trim().ifEmpty { null }) }),
                numbers,
            )
            plus.scope.launch {
                runCatching { plus.contacts.save(contact) }
                    .onSuccess { plus.nav.back() }
                    .onFailure { plus.say("No se pudo guardar el contacto") }
            }
        }
        override fun name(value: String) { draft = draft.copy(name = value) }
        override fun cardNumber(value: String) { draft = draft.copy(cardNumber = value.filter(Char::isDigit).take(16)) }
        override fun cardLabel(value: String) { draft = draft.copy(cardLabel = value.take(30)) }
        override fun scanCard() { scanning = true }
        override fun phone(id: String, value: String) {
            draft = draft.copy(phones = draft.phones.map { if (it.first == id) it.copy(second = Phones.input(value)) else it })
        }
        override fun removePhone(id: String) { draft = draft.copy(phones = draft.phones.filterNot { it.first == id }) }
        override fun addPhone() { draft = draft.copy(phones = draft.phones + (UUID.randomUUID().toString() to "")) }
    }
    ContactEditScreen(ContactEditUi(existing?.id, draft.name, card, phones, canSave), actions)
    if (scanning) CardScanner(phones = true, onClose = { scanning = false }) { number, phone ->
        if (number != null) draft = draft.copy(cardNumber = number)
        if (phone != null && draft.phones.none { it.second == phone }) {
            val empty = draft.phones.indexOfFirst { it.second.isEmpty() }
            draft = draft.copy(phones = if (empty >= 0) draft.phones.mapIndexed { i, p -> if (i == empty) p.copy(second = phone) else p } else draft.phones + (UUID.randomUUID().toString() to phone))
        }
    }
}
