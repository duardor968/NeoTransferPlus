package dev.duardo.neotransfer.plus.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.shell.Tab
import java.math.BigDecimal

/** Where the app is. The four tabs live under [Main]; everything else stacks on top. */
sealed interface Dest {
    data object Main : Dest
    /** [key]: `tx:<uuid>` (history), `j:<intent>` (payment of this phone), `m:<card>:<index>` (card record). */
    data class Operation(val key: String) : Dest
    data object Settings : Dest
    data object PasswordSettings : Dest
    data object About : Dest
    data object Licenses : Dest
    data class ContactDetail(val id: String) : Dest
    /** [prefill] carries what is already known of a new contact: a scanned number, a picked phone contact, a paid card. */
    data class ContactEdit(val id: String?, val prefill: Contact? = null) : Dest
    data object Recipient : Dest
    data class Amount(val recipient: RecipientUi, val amount: BigDecimal? = null, val fixed: Boolean = false, val description: String? = null) : Dest
    data class Review(val recipient: RecipientUi, val amount: BigDecimal, val cardId: String, val description: String?) : Dest
    data class Result(val intentId: String) : Dest
    /** [code]: a QR already read by the universal scanner, resolved on arrival. */
    data class Pay(val code: String? = null) : Dest
    data object Receive : Dest
    data class Service(val kind: ServiceKind, val reference: String = "") : Dest
    /** The universal scanner of the shortcut and the tile: QR codes and card or phone numbers. */
    data object Scan : Dest
}

/** One stacked destination; [id] keys its saved UI state while it stays in the stack. */
class Entry(val id: Long, val dest: Dest)

class Navigator {
    private var next = 0L
    val stack = mutableStateListOf(Entry(next++, Dest.Main))
    var tab by mutableStateOf(Tab.Home)
    /** Entries popped since the host last cleared their saved state. */
    val dropped = mutableStateListOf<Long>()

    val top: Entry get() = stack.last()

    fun push(dest: Dest) { stack += Entry(next++, dest) }

    /** Replaces the top destination: the flow moved on and back must not return to it. */
    fun replace(dest: Dest) {
        dropped += stack.removeAt(stack.lastIndex).id
        push(dest)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        dropped += stack.removeAt(stack.lastIndex).id
        return true
    }

    fun home(tab: Tab = Tab.Home) {
        while (stack.size > 1) dropped += stack.removeAt(stack.lastIndex).id
        this.tab = tab
    }
}
