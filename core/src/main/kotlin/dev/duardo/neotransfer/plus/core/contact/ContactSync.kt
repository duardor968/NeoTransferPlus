package dev.duardo.neotransfer.plus.core.contact

/** A card as both apps share it: the full number and its label. Card ids stay local to each app. */
data class SyncCard(val pan: String, val label: String?) {
    override fun toString() = "SyncCard(redacted)"
}

/**
 * One stored contact as both apps share it, deletions included: a tombstone ([deleted]) has no cards or phones.
 * [updated] is the epoch ms of the last local change, 0 for a record not changed since the apps started syncing.
 */
data class SyncContact(
    val id: String,
    val name: String,
    val phones: List<String>,
    val cards: List<SyncCard>,
    val updated: Long,
    val deleted: Boolean,
) {
    override fun toString() = "SyncContact(redacted)"
}

/**
 * The contacts NeoTransfer and NeoTransfer+ keep in step: each app exports its stored contacts (tombstones included)
 * and, when it opens, reads the other's and merges them per id. The newer record wins whole and keeps its time, so
 * nothing bounces back; records of the same time are joined.
 */
object ContactSync {
    /** The exported columns, in this order: `id`, `name`, `phones`, `cards`, `updated`, `deleted`. */
    val COLUMNS = arrayOf("id", "name", "phones", "cards", "updated", "deleted")

    /** The records to write locally so [local] takes in [remote]; each keeps the time it should be stored with. */
    fun merge(local: List<SyncContact>, remote: List<SyncContact>): List<SyncContact> {
        val byId = local.associateBy { it.id }
        return remote.mapNotNull { merge(byId[it.id], it) }
    }

    /** What to write for one id, or null when the local record stays as it is. */
    fun merge(local: SyncContact?, remote: SyncContact): SyncContact? = when {
        local == null -> remote.takeUnless { it.deleted }
        remote.updated > local.updated -> remote.takeUnless { it == local }
        remote.updated < local.updated -> null
        else -> join(local, remote).takeUnless { it == local }
    }

    /** Two records of the same time: everything either has, the local first; deleted only if both are. */
    private fun join(local: SyncContact, remote: SyncContact): SyncContact {
        val labels = remote.cards.associate { it.pan to it.label }
        val cards = local.cards.map { it.copy(label = it.label ?: labels[it.pan]) } +
            remote.cards.filter { card -> local.cards.none { it.pan == card.pan } }
        return local.copy(
            name = local.name.ifBlank { remote.name },
            phones = (local.phones + remote.phones).distinct(),
            cards = cards.distinctBy { it.pan },
            deleted = local.deleted && remote.deleted,
        )
    }

    /** One exported row, in [COLUMNS] order. */
    fun encode(contact: SyncContact): Array<Any> = arrayOf(
        contact.id,
        contact.name,
        contact.phones.joinToString("\n"),
        contact.cards.joinToString("\n") { "${it.pan}\t${it.label.orEmpty().replace(Separators, " ").trim()}" },
        contact.updated,
        if (contact.deleted) 1 else 0,
    )

    /**
     * One row read from the other app; null when it cannot be stored (no id, or a live contact without a name).
     * Malformed phones and cards are dropped; a tombstone carries none.
     */
    fun decode(id: String?, name: String?, phones: String?, cards: String?, updated: Long, deleted: Boolean): SyncContact? {
        if (id.isNullOrBlank() || !deleted && name.isNullOrBlank()) return null
        val numbers = phones.orEmpty().split('\n').filter { it.length == 8 && it.all(Char::isDigit) }.distinct()
        val saved = cards.orEmpty().split('\n').mapNotNull { line ->
            val pan = line.substringBefore('\t')
            if (pan.length != 16 || !pan.all(Char::isDigit)) return@mapNotNull null
            SyncCard(pan, line.substringAfter('\t', "").trim().ifEmpty { null })
        }.distinctBy { it.pan }
        return SyncContact(id, name.orEmpty().trim(), if (deleted) emptyList() else numbers, if (deleted) emptyList() else saved, updated, deleted)
    }

    private val Separators = Regex("[\t\r\n]")
}
