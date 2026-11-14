package dev.duardo.neotransfer.plus.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.runtime.compositionLocalOf
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.ui.model.PinProtection

/** What approves a payment (or opens the app) on this phone; the buttons that lead to its prompt show its icon. */
val LocalApproval = compositionLocalOf { PinProtection.Fingerprint }

/** The fingerprint where it alone approves, the lock where the screen lock or the access code does, none without either. */
@DrawableRes
fun PinProtection.icon(): Int? = when (this) {
    PinProtection.Fingerprint -> Ds.drawable.ic_fingerprint
    PinProtection.ScreenLock, PinProtection.Code -> Ds.drawable.ic_lock
    PinProtection.None -> null
}
