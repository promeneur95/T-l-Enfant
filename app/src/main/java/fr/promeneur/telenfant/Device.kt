package fr.promeneur.telenfant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Lampe torche. */
object Torch {
    var isOn = false
        private set
    private var registered = false

    private fun cameraId(c: Context): String? {
        val cm = c.getSystemService(CameraManager::class.java) ?: return null
        return try {
            cm.cameraIdList.firstOrNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null }
    }

    fun available(c: Context) = cameraId(c) != null

    /** Suit l'état réel de la lampe (elle peut s'éteindre si l'appareil photo s'ouvre). */
    fun watch(c: Context, onChange: () -> Unit) {
        if (registered) return
        val cm = c.getSystemService(CameraManager::class.java) ?: return
        val id = cameraId(c) ?: return
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == id) { isOn = enabled; onChange() }
                }
            }, Handler(Looper.getMainLooper()))
            registered = true
        } catch (_: Exception) {}
    }

    fun toggle(c: Context): Boolean {
        val cm = c.getSystemService(CameraManager::class.java) ?: return false
        val id = cameraId(c) ?: return false
        return try {
            cm.setTorchMode(id, !isOn)
            isOn = !isOn
            true
        } catch (_: Exception) { false }
    }
}

/** Sonnerie / vibreur. */
object Sound {
    fun isVibrate(c: Context): Boolean =
        c.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL

    fun setVibrate(c: Context, vibrate: Boolean): Boolean = try {
        c.getSystemService(AudioManager::class.java)?.ringerMode =
            if (vibrate) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        true
    } catch (_: Exception) { false }

    /** Réglage choisi par le parent, appliqué au démarrage du téléphone. */
    fun applyDefault(c: Context) {
        setVibrate(c, Store.defaultVibrate(c))
    }
}

/** Appareil photo du téléphone (Samsung ou autre), activable par le parent. */
object CameraApp {
    fun packageName(c: Context): String? {
        val pm = c.packageManager
        val samsung = "com.sec.android.app.camera"
        if (pm.getLaunchIntentForPackage(samsung) != null) return samsung
        return pm.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .firstOrNull { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(it.packageName) != null }
            ?.packageName
    }
}
