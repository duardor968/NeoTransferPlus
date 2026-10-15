package dev.duardo.neotransfer.plus.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.duardo.neotransfer.plus.app.routes.AboutRoute
import dev.duardo.neotransfer.plus.app.routes.ActivityRoute
import dev.duardo.neotransfer.plus.app.routes.AmountRoute
import dev.duardo.neotransfer.plus.app.routes.ContactEditRoute
import dev.duardo.neotransfer.plus.app.routes.ContactRoute
import dev.duardo.neotransfer.plus.app.routes.ContactsRoute
import dev.duardo.neotransfer.plus.app.routes.HomeRoute
import dev.duardo.neotransfer.plus.app.routes.LicensesRoute
import dev.duardo.neotransfer.plus.app.routes.OperationRoute
import dev.duardo.neotransfer.plus.app.routes.PasswordSettingsRoute
import dev.duardo.neotransfer.plus.app.routes.PayRoute
import dev.duardo.neotransfer.plus.app.routes.ReceiveRoute
import dev.duardo.neotransfer.plus.app.routes.RecipientRoute
import dev.duardo.neotransfer.plus.app.routes.ResultRoute
import dev.duardo.neotransfer.plus.app.routes.ReviewRoute
import dev.duardo.neotransfer.plus.app.routes.ScanRoute
import dev.duardo.neotransfer.plus.app.routes.ServiceRoute
import dev.duardo.neotransfer.plus.app.routes.ServicesRoute
import dev.duardo.neotransfer.plus.app.routes.SettingsRoute
import dev.duardo.neotransfer.plus.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.plus.ui.shell.MainShell
import dev.duardo.neotransfer.plus.ui.shell.Tab

/** The stack: a pushed screen slides in from the end, a popped one slides back; tabs swap in place. */
@Composable
fun NavHost(plus: PlusApp) {
    val nav = plus.nav
    val saved = rememberSaveableStateHolder()
    LaunchedEffect(nav.dropped.toList()) {
        nav.dropped.forEach { saved.removeState(it) }
        nav.dropped.clear()
    }
    BackHandler(nav.stack.size > 1) { nav.back() }
    BackHandler(nav.stack.size == 1 && nav.tab != Tab.Home) { nav.tab = Tab.Home }
    AnimatedContent(
        nav.top,
        transitionSpec = {
            val forward = targetState.id > initialState.id
            val slide = tween<androidx.compose.ui.unit.IntOffset>(NeoMotion.medium, easing = NeoMotion.emphasized)
            (slideInHorizontally(slide) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(NeoMotion.medium))) togetherWith
                (slideOutHorizontally(slide) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(NeoMotion.short)))
        },
        contentKey = { it.id },
        label = "screens",
    ) { entry ->
        saved.SaveableStateProvider(entry.id) { Screen(plus, entry.dest) }
    }
}

@Composable
private fun Screen(plus: PlusApp, dest: Dest) {
    when (dest) {
        Dest.Main -> Main(plus)
        is Dest.Operation -> OperationRoute(plus, dest.key)
        Dest.Settings -> SettingsRoute(plus)
        Dest.PasswordSettings -> PasswordSettingsRoute(plus)
        Dest.About -> AboutRoute(plus)
        Dest.Licenses -> LicensesRoute(plus)
        is Dest.ContactDetail -> ContactRoute(plus, dest.id)
        is Dest.ContactEdit -> ContactEditRoute(plus, dest)
        Dest.Recipient -> RecipientRoute(plus)
        is Dest.Amount -> AmountRoute(plus, dest)
        is Dest.Review -> ReviewRoute(plus, dest)
        is Dest.Result -> ResultRoute(plus, dest.intentId)
        is Dest.Pay -> PayRoute(plus, dest.code)
        Dest.Receive -> ReceiveRoute(plus)
        is Dest.Service -> ServiceRoute(plus, dest)
        Dest.Scan -> ScanRoute(plus)
    }
}

@Composable
private fun Main(plus: PlusApp) {
    val nav = plus.nav
    val tabs = rememberSaveableStateHolder()
    MainShell(nav.tab, { nav.tab = it }) {
        tabs.SaveableStateProvider(nav.tab.name) {
            when (nav.tab) {
                Tab.Home -> HomeRoute(plus)
                Tab.Activity -> ActivityRoute(plus)
                Tab.Contacts -> ContactsRoute(plus)
                Tab.Services -> ServicesRoute(plus)
            }
        }
    }
}
