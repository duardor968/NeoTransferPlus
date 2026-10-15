package dev.duardo.neotransfer.plus.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.duardo.neotransfer.plus.R

const val AppName = "NeoTransfer+"

/** Approved textured N in royal blue. Exports carry transparent margin: always Fit, never cropped. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.nt_brand_textured), AppName, modifier.size(size), contentScale = ContentScale.Fit)
}

/** Textured check. Only for operations ENZONA confirmed. */
@Composable
fun ConfirmedMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.nt_payment_success_textured), "Confirmada", modifier.size(size), contentScale = ContentScale.Fit)
}
