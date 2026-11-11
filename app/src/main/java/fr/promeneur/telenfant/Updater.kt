package fr.promeneur.telenfant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Mise à jour depuis la dernière « Release » GitHub (installation silencieuse en mode propriétaire). */
object Updater {
    const val REPO = "promeneur95/Tel_Enfant"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    fun currentVersion(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    fun check(c: Context, silent: Boolean, report: (String) -> Unit) {
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun say(s: String) = main.post { report(s) }
        Thread {
            try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "TelEnfant")
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val remote = json.optString("tag_name").filter { it.isDigit() }.toLongOrNull() ?: 0L
                val current = currentVersion(app)
                if (remote <= current) {
                    say("L'appli est à jour (version $current).")
                    return@Thread
                }
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                if (url == null) { say("Version $remote trouvée mais sans APK."); return@Thread }
                say("Téléchargement de la version $remote…")
                val file = File(app.cacheDir, "update.apk")
                download(url, file)
                install(app, file)
                say("Installation de la version $remote… L'appli va redémarrer.")
            } catch (e: Exception) {
                if (!silent) say("Vérification impossible : ${e.message}")
            }
        }.start()
    }

    private fun download(url: String, dest: File) {
        var u = url
        repeat(5) {
            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "TelEnfant")
                connectTimeout = 20000
                readTimeout = 60000
            }
            val code = conn.responseCode
            if (code in 300..399) {
                u = conn.getHeaderField("Location") ?: throw IllegalStateException("redirection vide")
                conn.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        throw IllegalStateException("trop de redirections")
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pi = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
        }
    }
}
