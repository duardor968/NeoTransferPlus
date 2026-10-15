package dev.duardo.neotransfer.plus.platform

import android.content.Context
import android.content.Intent
import dev.duardo.neotransfer.plus.MainActivity

/**
 * Screens the app can be opened on from outside (launcher shortcut, Quick Settings tile, notifications): the
 * launch intent carries the route in [EXTRA]. Opening still goes through the lock.
 */
object Routes {
    const val EXTRA = "dev.duardo.neotransfer.plus.route"
    const val SCANNER = "scanner"
    private const val OPERATION = "operation/"

    fun operation(key: String) = OPERATION + key
    fun operationKey(route: String): String? = route.removePrefix(OPERATION).takeIf { route.startsWith(OPERATION) && it.isNotEmpty() }
}

/** Opens the app on [route]; an open app receives it in `onNewIntent`. */
fun routeIntent(context: Context, route: String): Intent = Intent(context, MainActivity::class.java)
    .putExtra(Routes.EXTRA, route)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
