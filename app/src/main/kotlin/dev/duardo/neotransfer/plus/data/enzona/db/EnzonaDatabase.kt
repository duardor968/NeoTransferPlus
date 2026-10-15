package dev.duardo.neotransfer.plus.data.enzona.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import kotlinx.coroutines.flow.Flow

/** Remote read responses kept per owner. Session and payment authorization bodies never belong here. */
enum class EnzonaReadKind { ACCOUNT, CARDS, BALANCE }

@Entity(tableName = "read_cache", primaryKeys = ["ownerSubject", "kind", "resourceId"])
internal data class EnzonaCacheRow(
    val ownerSubject: String,
    val kind: EnzonaReadKind,
    val resourceId: String,
    val observedAt: Long,
    val json: String,
) {
    override fun toString(): String = "EnzonaCacheRow(kind=$kind, privateFields=redacted)"
}

/** The full PAN of an own card, typed by the user: ENZONA only returns last4. Optional alias. */
@Entity(tableName = "card_links", primaryKeys = ["ownerSubject", "cardUuid"])
data class EnzonaCardLink(
    val ownerSubject: String,
    val cardUuid: String,
    val pan: String? = null,
    val alias: String? = null,
) {
    override fun toString(): String = "EnzonaCardLink(redacted)"
}

/** Notification queue state of an incoming credit; the UI posts PENDING ones and marks them delivered. */
enum class EnzonaCreditNotice { NONE, PENDING, DELIVERED }

/** A sanitized remote history row keyed by its evidenced UUID and exact owner. */
@Entity(
    tableName = "transaction_history",
    primaryKeys = ["ownerSubject", "transactionUuid"],
    indices = [Index(value = ["ownerSubject", "notice"])],
)
internal data class EnzonaTransactionRow(
    val ownerSubject: String,
    val transactionUuid: String,
    val firstSeenAt: Long,
    val observedAt: Long,
    val json: String,
    val notice: EnzonaCreditNotice = EnzonaCreditNotice.NONE,
) {
    override fun toString(): String = "EnzonaTransactionRow(notice=$notice, privateFields=redacted)"
}

/** Set by the first recent-window history sync of an owner: credits seen before it are never notified. */
@Entity(tableName = "history_sync", primaryKeys = ["ownerSubject"])
internal data class EnzonaHistorySync(val ownerSubject: String, val syncedAt: Long)

@Dao
internal interface EnzonaDao {
    @Query("SELECT * FROM read_cache WHERE ownerSubject = :owner AND kind = :kind AND resourceId = :resourceId")
    fun cache(owner: String, kind: EnzonaReadKind, resourceId: String): EnzonaCacheRow?

    @Query("SELECT * FROM read_cache WHERE ownerSubject = :owner AND kind = :kind ORDER BY resourceId")
    fun observeCache(owner: String, kind: EnzonaReadKind): Flow<List<EnzonaCacheRow>>

    @Upsert fun putCache(row: EnzonaCacheRow)

    @Query("SELECT * FROM transaction_history WHERE ownerSubject = :owner AND transactionUuid IN (:ids)")
    fun transactions(owner: String, ids: List<String>): List<EnzonaTransactionRow>

    @Query("SELECT * FROM transaction_history WHERE ownerSubject = :owner ORDER BY firstSeenAt DESC, transactionUuid")
    fun observeTransactions(owner: String): Flow<List<EnzonaTransactionRow>>

    @Upsert fun putTransactions(rows: List<EnzonaTransactionRow>)

    @Query("SELECT * FROM transaction_history WHERE ownerSubject = :owner AND notice = 'PENDING' ORDER BY firstSeenAt, transactionUuid")
    fun observePendingNotices(owner: String): Flow<List<EnzonaTransactionRow>>

    @Query("UPDATE transaction_history SET notice = 'DELIVERED' WHERE ownerSubject = :owner AND notice = 'PENDING' AND transactionUuid IN (:ids)")
    fun markDelivered(owner: String, ids: List<String>): Int

    @Query("SELECT * FROM history_sync WHERE ownerSubject = :owner")
    fun historySync(owner: String): EnzonaHistorySync?

    @Upsert fun putHistorySync(row: EnzonaHistorySync)

    @Query("SELECT * FROM card_links WHERE ownerSubject = :owner ORDER BY cardUuid")
    fun observeCardLinks(owner: String): Flow<List<EnzonaCardLink>>

    @Upsert fun putCardLink(link: EnzonaCardLink)

    @Query("SELECT * FROM intent_journal WHERE ownerSubject = :owner AND id = :id")
    fun intent(owner: String, id: String): EnzonaJournalEntry?

    @Query("SELECT * FROM intent_journal WHERE ownerSubject = :owner ORDER BY preparedAt DESC, id")
    fun observeJournal(owner: String): Flow<List<EnzonaJournalEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIntent(entry: EnzonaJournalEntry): Long

    @Query("UPDATE intent_journal SET state = 'SUBMITTING' WHERE ownerSubject = :owner AND id = :id AND state = 'PREPARED'")
    fun tryBeginSubmission(owner: String, id: String): Int

    @Query("UPDATE intent_journal SET state = 'UNKNOWN' WHERE ownerSubject = :owner AND id = :id AND state = 'SUBMITTING'")
    fun markTimedOut(owner: String, id: String): Int

    @Query("DELETE FROM read_cache WHERE ownerSubject = :owner") fun deleteCache(owner: String)
    @Query("DELETE FROM transaction_history WHERE ownerSubject = :owner") fun deleteHistory(owner: String)
    @Query("DELETE FROM history_sync WHERE ownerSubject = :owner") fun deleteHistorySync(owner: String)
    @Query("DELETE FROM card_links WHERE ownerSubject = :owner") fun deleteCardLinks(owner: String)

    // No query edits reviewed intent fields, replaces journal rows, or deletes money records.
    @Query("UPDATE intent_journal SET state = :state, remoteId = :remoteId WHERE ownerSubject = :owner AND id = :id")
    fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?)
}

@Database(
    entities = [EnzonaCacheRow::class, EnzonaCardLink::class, EnzonaJournalEntry::class,
        EnzonaTransactionRow::class, EnzonaHistorySync::class],
    version = 1,
    exportSchema = true,
)
internal abstract class EnzonaDatabase : RoomDatabase() {
    abstract fun enzona(): EnzonaDao

    companion object {
        const val NAME = "enzona.db"

        fun open(context: Context, name: String = NAME): EnzonaDatabase {
            require(name.isNotBlank() && '/' !in name && '\\' !in name)
            return Room.databaseBuilder(context, EnzonaDatabase::class.java, name).build()
        }
    }
}
