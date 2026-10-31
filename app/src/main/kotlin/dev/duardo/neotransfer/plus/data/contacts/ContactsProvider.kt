package dev.duardo.neotransfer.plus.data.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import dev.duardo.neotransfer.plus.core.contact.ContactSync
import dev.duardo.neotransfer.plus.core.contact.SyncContact

/**
 * The stored contacts, tombstones included, for NeoTransfer to take in ([ContactSync]): `content://<app id>.contacts/contacts`,
 * read-only, and only to apps signed like this one (the `SYNC_CONTACTS` signature permission in the manifest).
 */
class ContactsProvider : ContentProvider() {
    private val store by lazy { ContactsStore.open(requireContext()) }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments != listOf(PATH)) return null
        return MatrixCursor(ContactSync.COLUMNS).apply { store.exported().forEach { addRow(ContactSync.encode(it)) } }
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments == listOf(PATH)) "vnd.android.cursor.dir/vnd.dev.duardo.neotransfer.contact" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    internal companion object {
        const val PATH = "contacts"
    }
}

/** NeoTransfer's [ContactsProvider] counterpart, read when this app opens. */
internal object PeerContacts {
    private val uri: Uri = "content://dev.duardo.neotransfer.contacts/${ContactsProvider.PATH}".toUri()

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
