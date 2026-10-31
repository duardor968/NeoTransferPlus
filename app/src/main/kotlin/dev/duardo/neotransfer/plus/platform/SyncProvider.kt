package dev.duardo.neotransfer.plus.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import dev.duardo.neotransfer.plus.app.accountActivity
import dev.duardo.neotransfer.plus.app.exportedActivity
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.core.activity.ActivitySync
import dev.duardo.neotransfer.plus.core.activity.SyncOperation
import dev.duardo.neotransfer.plus.core.contact.ContactSync
import dev.duardo.neotransfer.plus.core.contact.SyncContact
import dev.duardo.neotransfer.plus.data.EnzonaDataGraph
import dev.duardo.neotransfer.plus.data.contacts.ContactsStore
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What NeoTransfer reads from this app, read-only and only by apps signed like this one (the `SYNC_CONTACTS`
 * signature permission in the manifest), under `content://<app id>.sync`: `/contacts`, the stored contacts with their
 * tombstones ([ContactSync]), and `/activity`, the operations this app and ENZONA originated ([ActivitySync]).
 */
class SyncProvider : ContentProvider() {
    private val store by lazy { ContactsStore.open(requireContext()) }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        when (uri.pathSegments) {
            listOf(CONTACTS) -> MatrixCursor(ContactSync.COLUMNS).apply { store.exported().forEach { addRow(ContactSync.encode(it)) } }
            listOf(ACTIVITY) -> MatrixCursor(ActivitySync.COLUMNS).apply { activity().forEach { addRow(ActivitySync.encode(it)) } }
            else -> null
        }

    /** The ENZONA account's record as this app shows it; empty while signed out or when the stored session does not open in time. */
    private fun activity(): List<SyncOperation> = runBlocking {
        val repository = EnzonaDataGraph.get(requireContext()).repository
        withTimeoutOrNull(5_000) { repository.sessionState.first { it.restored } } ?: return@runBlocking emptyList()
        val cards = repository.cards.first().map { it.toUi() }
        val own = accountActivity(
            repository.transactions.first(), repository.journal.first(), repository.account.first()?.username,
            cards, store.contacts.first(), ZoneId.systemDefault(),
        )
        exportedActivity(own, System.currentTimeMillis())
    }

    override fun getType(uri: Uri): String? = when (uri.pathSegments) {
        listOf(CONTACTS) -> "vnd.android.cursor.dir/vnd.dev.duardo.neotransfer.contact"
        listOf(ACTIVITY) -> "vnd.android.cursor.dir/vnd.dev.duardo.neotransfer.operation"
        else -> null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    internal companion object {
        const val CONTACTS = "contacts"
        const val ACTIVITY = "activity"
        /** NeoTransfer's [SyncProvider] counterpart. */
        const val PEER = "content://dev.duardo.neotransfer.sync"
    }
}

/** NeoTransfer's contacts, read when this app opens. */
internal object PeerContacts {
    private val uri: Uri = "${SyncProvider.PEER}/${SyncProvider.CONTACTS}".toUri()

    /**
     * Everything NeoTransfer stores, tombstones included; null when it is not installed. Throws [SecurityException]
     * when it is not signed like this app. Blocking: off the main thread.
     */
    fun read(context: Context): List<SyncContact>? =
        context.contentResolver.query(uri, ContactSync.COLUMNS, null, null, null)?.use { cursor ->
            val column = ContactSync.COLUMNS.map(cursor::getColumnIndexOrThrow)
            buildList {
                while (cursor.moveToNext()) {
                    ContactSync.decode(
                        cursor.getString(column[0]), cursor.getString(column[1]), cursor.getString(column[2]), cursor.getString(column[3]),
                        cursor.getLong(column[4]), cursor.getInt(column[5]) != 0,
                    )?.let(::add)
                }
            }
        }
}

/** NeoTransfer's activity, read when this app opens and each time it returns. */
internal object PeerActivity {
    private val uri: Uri = "${SyncProvider.PEER}/${SyncProvider.ACTIVITY}".toUri()

    /**
     * What NeoTransfer did, in the shared window; null when it is not installed. Throws when it is not signed like
     * this app or does not serve the shared columns. Blocking: off the main thread.
     */
    fun read(context: Context): List<SyncOperation>? =
        context.contentResolver.query(uri, ActivitySync.COLUMNS, null, null, null)?.use { cursor ->
            val column = ActivitySync.COLUMNS.map(cursor::getColumnIndexOrThrow)
            val rows = buildList {
                while (cursor.moveToNext()) {
                    val text = { index: Int -> cursor.getString(column[index]) }
                    ActivitySync.decode(
                        text(0), text(1), text(2), text(3), cursor.getLong(column[4]), text(5), text(6), text(7), text(8), text(9), text(10),
                    )?.let(::add)
                }
            }
            ActivitySync.window(rows, System.currentTimeMillis())
        }
}
