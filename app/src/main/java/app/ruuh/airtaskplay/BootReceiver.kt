package app.ruuh.airtaskplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts the player and AirPlay server when the phone boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.QUICKBOOT_POWERON") {
            try { ContextCompat.startForegroundService(c, Intent(c, PlayerService::class.java).putExtra("boot", true)) } catch (_: Exception) {}
        }
    }
}
