package dev.duardo.neotransfer.plus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.PlusRoot
import dev.duardo.neotransfer.plus.platform.BiometricGate
import dev.duardo.neotransfer.plus.platform.Routes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        val gate = BiometricGate(this)
        setContent { PlusRoot(plus, gate) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** Shortcut, tile and notifications open a screen on top of the current one; the lock still comes first. */
    private fun route(intent: Intent) {
        val route = intent.getStringExtra(Routes.EXTRA) ?: return
        intent.removeExtra(Routes.EXTRA)
        val nav = plus.nav
        when {
            route == Routes.SCANNER -> if (nav.top.dest != Dest.Scan) nav.push(Dest.Scan)
            else -> Routes.operationKey(route)?.let { nav.push(Dest.Operation(it)) }
        }
    }
}
