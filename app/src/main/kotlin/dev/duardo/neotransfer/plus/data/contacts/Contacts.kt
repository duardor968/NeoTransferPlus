package dev.duardo.neotransfer.plus.data.contacts

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.withTransaction
import dev.duardo.neotransfer.plus.core.contact.ContactSync
import dev.duardo.neotransfer.plus.core.contact.SyncCard
import dev.duardo.neotransfer.plus.core.contact.SyncContact
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A person of the user's own agenda: several cards (full numbers) and several phones. Not tied to an ENZONA owner. */
data class Contact(val id: String, val name: String, val cards: List<ContactCard>, val phones: List<String>) {
    override fun toString() = "Contact(redacted)"
}

data class ContactCard(val id: String, val pan: String, val label: String?) {
    override fun toString() = "ContactCard(redacted)"
}

/**
 * A stored contact. A deleted one stays as a tombstone ([deleted], no cards or phones) so the other app does not bring
 * it back; [updated] is the epoch ms of the last change made here, 0 for one not changed since v2 ([ContactSync]).
 */
@Entity(tableName = "contacts")
internal data class ContactRow(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val updated: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(
    tableName = "contact_cards",
    foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("contactId")],
)
internal data class ContactCardRow(@PrimaryKey val id: String, val contactId: String, val pan: String, val label: String?, val position: Int)

@Entity(
    tableName = "contact_phones",
    foreignKeys = [ForeignKey(ContactRow::class, ["id"], ["contactId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("contactId")],
)
internal data class ContactPhoneRow(@PrimaryKey val id: String, val contactId: String, val number: String, val position: Int)

internal data class ContactWithDetails(
    @Embedded val contact: ContactRow,
    @Relation(parentColumn = "id", entityColumn = "contactId") val cards: List<ContactCardRow>,
    @Relation(parentColumn = "id", entityColumn = "contactId") val phones: List<ContactPhoneRow>,
)

@Dao
internal interface ContactsDao {
    @Transaction
    @Query("SELECT * FROM contacts WHERE deleted = 0")
    fun observe(): Flow<List<ContactWithDetails>>

    /** Every stored contact, tombstones included. */
    @Transaction
    @Query("SELECT * FROM contacts")
    fun all(): List<ContactWithDetails>

    @Query("SELECT * FROM contact_cards WHERE contactId = :id") fun cards(id: String): List<ContactCardRow>
    @Upsert fun putContact(row: ContactRow)
    @Insert fun putCards(rows: List<ContactCardRow>)
    @Insert fun putPhones(rows: List<ContactPhoneRow>)
    @Query("DELETE FROM contact_cards WHERE contactId = :id") fun deleteCards(id: String)
    @Query("DELETE FROM contact_phones WHERE contactId = :id") fun deletePhones(id: String)
    @Query("UPDATE contacts SET deleted = 1, updated = :at WHERE id = :id") fun bury(id: String, at: Long)
}

/** v2 keeps tombstones and change times for [ContactSync]; every v1 contact stays, live and never changed (0). */
@Database(
    entities = [ContactRow::class, ContactCardRow::class, ContactPhoneRow::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class ContactsDatabase : RoomDatabase() {
    abstract fun contacts(): ContactsDao

    companion object {
        const val NAME = "contacts.db"
        @Volatile private var shared: ContactsDatabase? = null

        /** The process's one instance, shared by the app and [ContactsProvider] (which may be asked first). */
        fun get(context: Context): ContactsDatabase =
            shared ?: synchronized(this) { shared ?: open(context.applicationContext).also { shared = it } }

        fun open(context: Context, name: String = NAME): ContactsDatabase {
            require(name.isNotBlank() && '/' !in name && '\\' !in name)
            return Room.databaseBuilder(context, ContactsDatabase::class.java, name).build()
        }
    }
}

/**
 * What the app keeps of people: contacts kept only in the app, and the cards of people in the phone's address book
 * ([Directory]). Saving replaces a contact's cards and phones as a whole, in one transaction, and stamps it with
 * [now]; deleting leaves a tombstone. Both are what the other app takes in ([ContactSync]).
 */
class ContactsStore internal constructor(private val database: ContactsDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.contacts()

    val contacts: Flow<List<Contact>> = dao.observe().map { rows ->
        rows.map { row ->
            Contact(
                row.contact.id, row.contact.name,
                row.cards.sortedBy { it.position }.map { ContactCard(it.id, it.pan, it.label) },
                row.phones.sortedBy { it.position }.map { it.number },
            )
        }
    }

    suspend fun save(contact: Contact) {
        require(contact.name.isNotBlank())
        require(contact.cards.all { card -> card.pan.length == 16 && card.pan.all { it in '0'..'9' } })
        database.withTransaction { write(contact) }
    }

    /** Removes [delete] and then saves [save], in one transaction: cards moved between contacts keep their ids. */
    suspend fun relink(save: List<Contact>, delete: List<String>) {
        database.withTransaction {
            val at = now()
            delete.forEach { bury(it, at) }
            save.forEach { write(it, at) }
        }
    }

    private fun write(contact: Contact, updated: Long = now(), deleted: Boolean = false) {
        dao.putContact(ContactRow(contact.id, contact.name.trim(), updated, deleted))
        dao.deleteCards(contact.id)
        dao.deletePhones(contact.id)
        dao.putCards(contact.cards.mapIndexed { index, card -> ContactCardRow(card.id, contact.id, card.pan, card.label?.trim()?.ifEmpty { null }, index) })
        dao.putPhones(contact.phones.mapIndexed { index, phone -> ContactPhoneRow("${contact.id}:$index", contact.id, phone, index) })
    }

    private fun bury(id: String, at: Long) {
        dao.deleteCards(id)
        dao.deletePhones(id)
        dao.bury(id, at)
    }

    /** Leaves a tombstone the lists never show; saving the id again brings it back. */
    suspend fun delete(id: String) {
        database.withTransaction { bury(id, now()) }
    }

    /** Every stored contact as the other app reads it, tombstones included. Blocking: off the main thread. */
    fun exported(): List<SyncContact> = database.runInTransaction<List<SyncContact>> { dao.all().map { it.toSync() } }

    /**
     * Takes in the other app's contacts ([ContactSync.merge]), each with the time it came with. A card keeps its id
     * when its number was already saved for that contact. Returns how many contacts changed.
     */
    suspend fun merge(remote: List<SyncContact>): Int = database.withTransaction {
        val writes = ContactSync.merge(dao.all().map { it.toSync() }, remote)
        for (contact in writes) {
            val ids = dao.cards(contact.id).associate { it.pan to it.id }
            val cards = contact.cards.map { ContactCard(ids[it.pan] ?: UUID.randomUUID().toString(), it.pan, it.label) }
            write(Contact(contact.id, contact.name, cards, contact.phones), contact.updated, contact.deleted)
        }
        writes.size
    }

    private fun ContactWithDetails.toSync() = SyncContact(
        contact.id, contact.name,
        phones.sortedBy { it.position }.map { it.number },
        cards.sortedBy { it.position }.map { SyncCard(it.pan, it.label) },
        contact.updated, contact.deleted,
    )

    companion object {
        fun open(context: Context) = ContactsStore(ContactsDatabase.get(context))
    }
}
