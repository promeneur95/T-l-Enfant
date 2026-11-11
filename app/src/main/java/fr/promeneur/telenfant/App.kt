package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.TextView
import android.widget.Toast

/** Application : enregistre tout plantage pour pouvoir l'afficher au lancement suivant. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val version = try { Updater.currentVersion(this) } catch (_: Throwable) { -1L }
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                    .putString("last", "Version $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                        "Mode : ${Store.mode(this)} · Écran : ${thread.name}\n\n" + Log.getStackTraceString(e).take(6000))
                    .commit()
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }
}

/** Affiche le dernier plantage (à copier pour le transmettre). */
object CrashReport {
    fun showIfAny(a: Activity) {
        val prefs = a.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val text = prefs.getString("last", null) ?: return
        prefs.edit().remove("last").apply()
        val view = TextView(a).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8))
        }
        AlertDialog.Builder(a)
            .setTitle("L'appli s'est arrêtée la dernière fois")
            .setView(android.widget.ScrollView(a).apply { addView(view) })
            .setPositiveButton("Copier le rapport") { _, _ ->
                a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Rapport Tél Enfant", text))
                Toast.makeText(a, "Rapport copié : collez-le dans la conversation avec Claude", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
