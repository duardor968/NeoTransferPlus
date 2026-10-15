package dev.duardo.neotransfer.plus.app.routes

import android.net.Uri
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
import dev.duardo.neotransfer.plus.app.Banks
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactCard
import dev.duardo.neotransfer.plus.platform.copyText
import dev.duardo.neotransfer.plus.platform.cubanMobile
import dev.duardo.neotransfer.plus.platform.readPickedContact
import dev.duardo.neotransfer.plus.scanner.QrVerdict
import dev.duardo.neotransfer.plus.scanner.ScanMode
import dev.duardo.neotransfer.plus.scanner.ScanResult
import dev.duardo.neotransfer.plus.scanner.ScannerView
import dev.duardo.neotransfer.plus.ui.contacts.ContactActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactEditActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactsActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.plus.ui.model.ContactEditUi
import dev.duardo.neotransfer.plus.ui.model.ContactsContent
import dev.duardo.neotransfer.plus.ui.model.ContactsUi
import dev.duardo.neotransfer.plus.ui.model.EditableCardUi
import dev.duardo.neotransfer.plus.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Name, card numbers and phones as they are typed or found in a contact. */
fun Contact.matches(query: String): Boolean {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return true
    val digits = needle.filter(Char::isDigit)
    return needle in name.lowercase(Locale.ROOT) ||
        (digits.isNotEmpty() && (cards.any { digits in it.pan } || phones.any { digits in it }))
}

@Composable
fun ContactsRoute(plus: PlusApp) {
    val context = LocalContext.current
    val contacts by plus.contactList.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
        if (uri != null) plus.scope.launch {
            val picked = runCatching { withContext(Dispatchers.IO) { context.readPickedContact(uri) } }
            picked.onSuccess { plus.nav.push(Dest.ContactEdit(null, it)) }
                .onFailure { plus.say(it.message ?: "No se pudo leer el contacto") }
        }
    }
    val all = contacts.orEmpty()
    val content = when {
        all.isEmpty() -> ContactsContent.Empty
        else -> all.filter { it.matches(query) }.let { if (it.isEmpty()) ContactsContent.NoResults else ContactsContent.Items(it.map(Contact::toUi)) }
    }
    val actions = remember(plus) {
        object : ContactsActions {
            override fun query(value: String) { query = value }
            override fun open(id: String) = plus.nav.push(Dest.ContactDetail(id))
            override fun create() = plus.nav.push(Dest.ContactEdit(null))
            override fun importFromPhone() = picker.launch(null)
        }
    }
    ContactsScreen(ContactsUi(query, content), actions)
}

@Composable
fun ContactRoute(plus: PlusApp, id: String) {
    val context = LocalContext.current
    val contacts by plus.contactList.collectAsState()
    val contact = contacts?.firstOrNull { it.id == id }
    var confirming by remember { mutableStateOf(false) }
    // Deleted (here or meanwhile): the detail closes once the agenda is loaded.
    val gone = contact == null && contacts != null
    LaunchedEffect(gone) { if (gone) plus.nav.back() }
    if (contact == null) return
    val ui = contact.toUi()
    val actions = object : ContactActions {
        override fun back() { plus.nav.back() }
        override fun edit() = plus.nav.push(Dest.ContactEdit(id))
        override fun transfer(cardId: String) {
            val card = ui.cards.firstOrNull { it.id == cardId } ?: return
            plus.nav.push(Dest.Amount(RecipientUi.Card(card, ui.name, ui.id)))
        }
        override fun copy(value: String) = context.copyText("Número", value)
        override fun delete() { confirming = true }
        override fun confirmDelete() {
            confirming = false
            plus.scope.launch { plus.contacts.delete(id) }
        }
        override fun cancelDelete() { confirming = false }
    }
    ContactDetailScreen(ui, actions, confirmingDelete = confirming)
}

/** The contact being edited; saved across rotation as plain strings. */
private data class Draft(val name: String, val cards: List<Triple<String, String, String>>, val phones: List<Pair<String, String>>)

private val DraftSaver = listSaver<Draft, String>(
    save = { draft ->
        listOf(draft.name, draft.cards.size.toString()) + draft.cards.flatMap { listOf(it.first, it.second, it.third) } +
            draft.phones.flatMap { listOf(it.first, it.second) }
    },
    restore = { values ->
        val count = values[1].toInt()
        val cards = (0 until count).map { Triple(values[2 + it * 3], values[3 + it * 3], values[4 + it * 3]) }
        val phones = values.drop(2 + count * 3).chunked(2).map { it[0] to it[1] }
        Draft(values[0], cards, phones)
    },
)

private fun phoneError(digits: String): String? = when {
    digits.isEmpty() -> null
    digits.length < 8 -> "Número incompleto"
    cubanMobile(digits) == null -> "No es un móvil cubano"
    else -> null
}

@Composable
fun ContactEditRoute(plus: PlusApp, dest: Dest.ContactEdit) {
    val contacts by plus.contactList.collectAsState()
    val existing = dest.id?.let { id -> contacts?.firstOrNull { it.id == id } }
    val start = existing ?: dest.prefill
    var draft by rememberSaveable(start?.id, stateSaver = DraftSaver) {
        mutableStateOf(
            Draft(
                start?.name.orEmpty(),
                start?.cards?.map { Triple(it.id, it.pan, it.label.orEmpty()) }.orEmpty(),
                start?.phones?.map { UUID.randomUUID().toString() to it }.orEmpty(),
            ),
        )
    }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val cards = draft.cards.map { (id, number, label) -> EditableCardUi(id, number, label, Banks.ofPan(number), cardNumberError(number)) }
    val phones = draft.phones.map { (id, number) -> EditablePhoneUi(id, number, phoneError(number)) }
    val canSave = draft.name.isNotBlank() &&
        draft.cards.all { it.second.isEmpty() || cardNumberError(it.second) == null } &&
        draft.phones.all { it.second.isEmpty() || phoneError(it.second) == null }
    val actions = object : ContactEditActions {
        override fun back() { plus.nav.back() }
        override fun save() {
            if (!canSave) return
            val contact = Contact(
                existing?.id ?: UUID.randomUUID().toString(),
                draft.name.trim(),
                draft.cards.filter { it.second.isNotEmpty() }.map { ContactCard(it.first, it.second, it.third.trim().ifEmpty { null }) },
                draft.phones.map { it.second }.filter(String::isNotEmpty).distinct(),
            )
            plus.scope.launch {
                runCatching { plus.contacts.save(contact) }
                    .onSuccess { plus.nav.back() }
                    .onFailure { plus.say("No se pudo guardar el contacto") }
            }
        }
        override fun name(value: String) { draft = draft.copy(name = value) }
        override fun cardNumber(id: String, value: String) {
            draft = draft.copy(cards = draft.cards.map { if (it.first == id) it.copy(second = value.filter(Char::isDigit).take(16)) else it })
        }
        override fun cardLabel(id: String, value: String) {
            draft = draft.copy(cards = draft.cards.map { if (it.first == id) it.copy(third = value) else it })
        }
        override fun removeCard(id: String) { draft = draft.copy(cards = draft.cards.filterNot { it.first == id }) }
        override fun addCard() { draft = draft.copy(cards = draft.cards + Triple(UUID.randomUUID().toString(), "", "")) }
        override fun scanCard() { scanning = true }
        override fun phone(id: String, value: String) {
            draft = draft.copy(phones = draft.phones.map { if (it.first == id) it.copy(second = value.filter(Char::isDigit).take(8)) else it })
        }
        override fun removePhone(id: String) { draft = draft.copy(phones = draft.phones.filterNot { it.first == id }) }
        override fun addPhone() { draft = draft.copy(phones = draft.phones + (UUID.randomUUID().toString() to "")) }
    }
    ContactEditScreen(ContactEditUi(existing?.id, draft.name, cards, phones, canSave), actions)
    if (scanning) {
        BackHandler { scanning = false }
        ScannerView(
            mode = ScanMode.NUMBERS,
            classifyQr = { QrVerdict.Rejected("Aquí se leen números de tarjeta o de móvil") },
            onResult = { result ->
                scanning = false
                draft = when (result) {
                    is ScanResult.CardNumber -> draft.copy(cards = draft.cards.filter { it.second.isNotEmpty() } + Triple(UUID.randomUUID().toString(), result.digits, ""))
                    is ScanResult.PhoneNumber -> draft.copy(phones = draft.phones.filter { it.second.isNotEmpty() } + (UUID.randomUUID().toString() to result.digits))
                    is ScanResult.Qr -> draft
                }
            },
            onClose = { scanning = false },
        )
    }
}
