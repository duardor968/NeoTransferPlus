package dev.duardo.neotransfer.plus.platform

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "Escanear": opens the app on the scanner. */
class ScanTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, routeIntent(this, Routes.SCANNER), PendingIntent.FLAG_IMMUTABLE))
    }
}
