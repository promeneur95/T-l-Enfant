package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.File

/** Sonneries : celles du téléphone + celles envoyées par les parents. */
object Ringtones {
    data class Choice(val title: String, val source: String) // source : chemin de fichier ou URI content://

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ringtones", Context.MODE_PRIVATE)
    fun dir(c: Context) = File(c.filesDir, "ringtones").apply { mkdirs() }

    fun current(c: Context): String = prefs(c).getString("current", "") ?: ""
    fun currentTitle(c: Context): String = prefs(c).getString("current_title", "Sonnerie du téléphone") ?: ""
    fun set(c: Context, choice: Choice) =
        prefs(c).edit().putString("current", choice.source).putString("current_title", choice.title).apply()

    /** Ajoute un fichier audio reçu des parents. */
    fun addCustom(c: Context, name: String, bytes: ByteArray): Choice {
        val safe = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60).ifBlank { "son" }
        val f = File(dir(c), safe).apply { writeBytes(bytes) }
        return Choice("🎁 " + safe.substringBeforeLast('.'), f.absolutePath)
    }

    fun choices(c: Context): List<Choice> {
        val out = mutableListOf<Choice>()
        dir(c).listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
            out += Choice("🎁 " + it.name.substringBeforeLast('.'), it.absolutePath)
        }
        try {
            val rm = RingtoneManager(c).apply { setType(RingtoneManager.TYPE_RINGTONE) }
            val cur = rm.cursor
            while (cur.moveToNext()) {
                val title = cur.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                out += Choice("🎵 $title", rm.getRingtoneUri(cur.position).toString())
            }
        } catch (_: Exception) {}
        return out
    }

    fun prepare(c: Context, player: MediaPlayer, source: String) {
        if (source.startsWith("/")) player.setDataSource(source) else player.setDataSource(c, Uri.parse(source))
    }
}

/** Sonnerie des appels entrants, jouée par l'appli (pour pouvoir utiliser n'importe quel son). */
object CallRinger {
    private var player: MediaPlayer? = null
    private var vibrating = false

    fun start(c: Context) {
        stop(c)
        val am = c.getSystemService(AudioManager::class.java)
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sources = listOf(Ringtones.current(c), RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)?.toString() ?: "",
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.toString() ?: "")
                .filter { it.isNotBlank() }
            for (src in sources) {
                try {
                    player = MediaPlayer().apply {
                        setAudioAttributes(attrs)
                        Ringtones.prepare(c, this, src)
                        isLooping = true
                        prepare()
                        start()
                    }
                    break
                } catch (_: Exception) {
                    try { player?.release() } catch (_: Exception) {}
                    player = null
                }
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            try {
                c.getSystemService(Vibrator::class.java)?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                )
                vibrating = true
            } catch (_: Exception) {}
        }
    }

    fun stop(c: Context) {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        if (vibrating) {
            try { c.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
            vibrating = false
        }
    }
}

/** Choix de la sonnerie par l'enfant, avec écoute. */
object RingtonePicker {
    fun show(a: Activity) {
        val list = Ringtones.choices(a)
        if (list.isEmpty()) { android.widget.Toast.makeText(a, "Aucune sonnerie disponible", android.widget.Toast.LENGTH_SHORT).show(); return }
        var selected = list.indexOfFirst { it.source == Ringtones.current(a) }
        var preview: MediaPlayer? = null
        fun stopPreview() { try { preview?.stop(); preview?.release() } catch (_: Exception) {}; preview = null }
        AlertDialog.Builder(a)
            .setTitle("🎵 Choisis ta sonnerie")
            .setSingleChoiceItems(list.map { it.title }.toTypedArray(), selected) { _, which ->
                selected = which
                stopPreview()
                try {
                    preview = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        Ringtones.prepare(a, this, list[which].source)
                        prepare(); start()
                    }
                } catch (_: Exception) {}
            }
            .setPositiveButton("Choisir") { _, _ ->
                stopPreview()
                if (selected >= 0) {
                    Ringtones.set(a, list[selected])
                    android.widget.Toast.makeText(a, "Sonnerie : ${list[selected].title}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler") { _, _ -> stopPreview() }
            .setOnDismissListener { stopPreview() }
            .show()
    }
}
