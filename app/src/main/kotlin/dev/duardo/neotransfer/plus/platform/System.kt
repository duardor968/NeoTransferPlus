package dev.duardo.neotransfer.plus.platform

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.duardo.neotransfer.plus.core.phone.Phones
import dev.duardo.neotransfer.plus.data.contacts.PhoneBook
import dev.duardo.neotransfer.plus.data.contacts.PhoneContact
import java.io.File

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

/** The app's page in the system settings: where a permission refused for good is given. */
fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The person in the phone's Contacts app, to change their name or phones; it comes back here once saved. */
fun Context.editInContacts(lookupKey: String) {
    startActivity(
        Intent(Intent.ACTION_EDIT, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey)))
            .putExtra("finishActivityOnSaveCompleted", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

// ---- Running with the app closed ----------------------------------------------------------------------------

/** Whether battery optimization leaves the periodic check alone. */
fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** The system dialog that exempts this app from battery optimization (BatteryLife is a Google Play rule; the app is not on Play). */
@SuppressLint("BatteryLife")
fun Context.batteryExemptionRequest(): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))

/** Transsion's "Inicio automático" list, seen on TECNO: without it, its power manager stops WorkManager. */
private const val TranssionAutostart = "com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY"

/** The phone's own autostart list, when it has one this app knows. Declared in the manifest's `<queries>`. */
fun Context.autostartSettings(): Intent? = Intent(TranssionAutostart).takeIf { it.resolveActivity(packageManager) != null }

/** Opens [autostartSettings]; false when the phone has none or refuses to open it. */
fun Context.openAutostartSettings(): Boolean {
    val intent = autostartSettings() ?: return false
    return runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** `5xxxxxxx` or `6xxxxxxx`, with `+53`, `53` or `0053` tolerated; anything else (landlines, foreign numbers) is not a mobile. */
fun cubanMobile(raw: String): String? {
    if (raw.isBlank() || raw.any { !it.isDigit() && it !in "+ -()." }) return null
    return Phones.normalize(raw)?.takeIf { it[0] in "56" }
}

/** The address book through the phones table: one query, grouped by contact; call off the main thread. */
class AndroidPhoneBook(context: Context) : PhoneBook {
    private val resolver = context.applicationContext.contentResolver

    override fun read(): List<PhoneContact> {
        val rows = resolver.query(Phone.CONTENT_URI, arrayOf(Phone.LOOKUP_KEY, Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null, null) ?: return emptyList()
        val people = linkedMapOf<String, PhoneContact>()
        rows.use { cursor ->
            while (cursor.moveToNext()) {
                val phone = cursor.getString(3)?.let(::cubanMobile) ?: continue
                val name = cursor.getString(2)?.trim()?.takeIf(String::isNotEmpty) ?: continue
                val key = cursor.getString(0)?.takeIf(String::isNotBlank) ?: "id-${cursor.getLong(1)}"
                val known = people[key]
                people[key] = if (known == null) PhoneContact(key, name, listOf(phone)) else known.copy(phones = (known.phones + phone).distinct())
            }
        }
        return people.values.toList()
    }
}

/** Shares the receive QR as an image (white plate, dark modules) with [caption] as its text. */
fun Context.shareQr(payload: String, caption: String) {
    val matrix = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val scale = 1024 / matrix.width
    val side = matrix.width * scale
    val pixels = IntArray(side * side) { index ->
        if (matrix[(index % side) / scale, (index / side) / scale]) 0xFF111316.toInt() else 0xFFFFFFFF.toInt()
    }
    val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    val folder = File(cacheDir, "shared").apply { mkdirs() }
    val file = File(folder, "cobro.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Compartir código").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
