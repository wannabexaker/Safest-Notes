package com.tezas.safestnotes.widget

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.tezas.safestnotes.R
import com.tezas.safestnotes.ui.QuickCaptureActivity

/**
 * Quick Settings Tile — appears in Android notification shade quick-toggles.
 * Tap: opens Dictate quick capture (fastest path to capture a thought).
 * Long-tap: opens New Note.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickCaptureTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.quick_capture_label)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickCaptureActivity::class.java).apply {
            action = QuickCaptureActivity.ACTION_DICTATE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
