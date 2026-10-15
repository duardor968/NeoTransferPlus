package dev.duardo.neotransfer.plus.data.contacts

import android.content.Context
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A person of the user's own agenda: several cards (full numbers) and several phones. Not tied to an ENZONA owner. */
data class Contact(val id: String, val name: String, val cards: List<ContactCard>, val phones: List<String>) {
    override fun toString() = "Contact(redacted)"
}

data class ContactCard(val id: String, val pan: String, val label: String?) {
    override fun toString() = "ContactCard(redacted)"
}

@Entity(tableName = "contacts")
internal data class ContactRow(@PrimaryKey val id: String, val name: String)

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
    @Query("SELECT * FROM contacts")
    fun observe(): Flow<List<ContactWithDetails>>

    @Upsert fun putContact(row: ContactRow)
    @Insert fun putCards(rows: List<ContactCardRow>)
    @Insert fun putPhones(rows: List<ContactPhoneRow>)
    @Query("DELETE FROM contact_cards WHERE contactId = :id") fun deleteCards(id: String)
    @Query("DELETE FROM contact_phones WHERE contactId = :id") fun deletePhones(id: String)
    @Query("DELETE FROM contacts WHERE id = :id") fun delete(id: String)
}

@Database(entities = [ContactRow::class, ContactCardRow::class, ContactPhoneRow::class], version = 1, exportSchema = true)
internal abstract class ContactsDatabase : RoomDatabase() {
    abstract fun contacts(): ContactsDao

    companion object {
        fun open(context: Context): ContactsDatabase =
            Room.databaseBuilder(context, ContactsDatabase::class.java, "contacts.db").build()
    }
}

/** The local agenda. Saving replaces a contact's cards and phones as a whole, in one transaction. */
class ContactsStore internal constructor(private val database: ContactsDatabase) {
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
        database.withTransaction {
            dao.putContact(ContactRow(contact.id, contact.name.trim()))
            dao.deleteCards(contact.id)
            dao.deletePhones(contact.id)
            dao.putCards(contact.cards.mapIndexed { index, card -> ContactCardRow(card.id, contact.id, card.pan, card.label?.trim()?.ifEmpty { null }, index) })
            dao.putPhones(contact.phones.mapIndexed { index, phone -> ContactPhoneRow("${contact.id}:$index", contact.id, phone, index) })
        }
    }

    suspend fun delete(id: String) {
        database.withTransaction { dao.delete(id) }
    }

    companion object {
        fun open(context: Context) = ContactsStore(ContactsDatabase.open(context.applicationContext))
    }
}
