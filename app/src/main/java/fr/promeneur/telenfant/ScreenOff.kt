package fr.promeneur.telenfant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** Ferme un écran secondaire quand l'écran s'éteint, pour retrouver l'écran de veille au réveil. */
object ScreenOff {
    private val receivers = mutableMapOf<Activity, BroadcastReceiver>()

    fun closeOnScreenOff(a: Activity) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = a.finish()
        }
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        try {
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else a.registerReceiver(r, f)
            receivers[a] = r
        } catch (_: Exception) {}
    }

    fun release(a: Activity) {
        receivers.remove(a)?.let { try { a.unregisterReceiver(it) } catch (_: Exception) {} }
    }
}
