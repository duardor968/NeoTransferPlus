package dev.duardo.neotransfer.plus.platform

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import dev.duardo.neotransfer.plus.data.contacts.Contact
import java.util.UUID

/** Share sheet with plain text (receipts, receive codes). */
fun Context.shareText(text: String, title: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Card numbers are not secrets for this app: plain clip, no sensitive flag. */
fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun Context.openUrl(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** `5xxxxxxx`, with `+53`, `53` or `0053` tolerated; anything else (landlines, foreign numbers) is not a mobile. */
fun cubanMobile(raw: String): String? {
    if (raw.isBlank() || raw.any { !it.isDigit() && it !in "+ -()." }) return null
    val digits = raw.filter(Char::isDigit)
    val local = when {
        digits.length == 8 -> digits
        digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
        digits.length == 12 && digits.startsWith("0053") -> digits.drop(4)
        else -> return null
    }
    return local.takeIf { it[0] == '5' }
}

/**
 * Reads only the contact the user picked (`ACTION_PICK` on Contacts): name and Cuban mobiles. Needs READ_CONTACTS for
 * the phones; without it the name alone comes in. Call off the main thread.
 */
fun Context.readPickedContact(uri: Uri): Contact {
    require(uri.scheme == "content" && uri.authority == ContactsContract.AUTHORITY) { "El contacto elegido no es válido" }
    val (id, name) = checkNotNull(contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)) {
        "No se pudo leer el contacto"
    }.use { cursor ->
        require(cursor.moveToFirst()) { "El contacto ya no está disponible" }
        cursor.getLong(0) to cursor.getString(1)?.trim().orEmpty()
    }
    require(name.isNotEmpty()) { "El contacto no tiene nombre" }
    val phones = if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
        checkNotNull(contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null)) { "No se pudieron leer los teléfonos" }
            .use { cursor -> buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) } }
            .mapNotNull(::cubanMobile).distinct()
    } else emptyList()
    return Contact(UUID.randomUUID().toString(), name, emptyList(), phones)
}

/** Shares the receive QR as an image (white plate, dark modules) with [caption] as its text. */
fun Context.shareQr(payload: String, caption: String) {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        payload, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
        mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H,
            com.google.zxing.EncodeHintType.MARGIN to 4,
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val scale = 1024 / matrix.width
    val side = matrix.width * scale
    val pixels = IntArray(side * side) { index ->
        if (matrix[(index % side) / scale, (index / side) / scale]) 0xFF111316.toInt() else 0xFFFFFFFF.toInt()
    }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val folder = java.io.File(cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(folder, "cobro.png")
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Compartir código").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
