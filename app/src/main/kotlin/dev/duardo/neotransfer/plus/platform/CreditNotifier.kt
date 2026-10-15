package dev.duardo.neotransfer.plus.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import dev.duardo.neotransfer.plus.R

/** "Contacto e importe" for every received transfer; the lock screen only sees a generic public version. */
class CreditNotifier(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferencias recibidas", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun enabled(): Boolean = manager.areNotificationsEnabled() &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Returns false when notifications are not allowed: the notice stays queued for the next chance. */
    fun post(key: String, title: String, operationKey: String): Boolean {
        if (!enabled()) return false
        val open = PendingIntent.getActivity(context, 0, routeIntent(context, Routes.operation(operationKey)).setIdentifier(key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Transferencia recibida")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(key, 0, notification)
        return true
    }

    private companion object { const val CHANNEL = "incoming_transfers" }
}
