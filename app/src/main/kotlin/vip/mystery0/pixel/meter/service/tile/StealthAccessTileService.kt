package com.kakao.taxi.service.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kakao.taxi.MainActivity
import com.kakao.taxi.R
import com.kakao.taxi.util.StealthHelper

/**
 * Quick Settings tile that opens NowBrief directly — the one entry point
 * that still works after the launcher icon has been hidden via Stealth Mode
 * (Settings → General → Stealth Mode).
 *
 * The tile's subtitle reflects whether stealth mode is currently active,
 * purely as a status indicator (toggling stealth mode itself is done from
 * the Settings screen, not from the tile, to avoid accidental hides).
 */
class StealthAccessTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val hidden = StealthHelper.isIconHidden(this)
        tile.label = getString(R.string.tile_stealth_label)
        tile.subtitle = if (hidden) "Stealth mode active" else "Icon visible"
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
