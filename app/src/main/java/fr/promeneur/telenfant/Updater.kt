package fr.promeneur.telenfant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Mise à jour depuis la dernière « Release » GitHub (installation silencieuse en mode propriétaire). */
object Updater {
    const val REPO = "promeneur95/T-l-Enfant"
    /** Page web (pas l'API GitHub, limitée à 60 requêtes/heure par adresse IP, partagée en 4G). */
    private const val LATEST = "https://github.com/$REPO/releases/latest"
    private const val APK = "$LATEST/download/TelEnfant.apk"

    fun currentVersion(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Numéro de la dernière version publiée, lu dans la redirection « releases/latest → releases/tag/vN ». */
    private fun latestVersion(): Long {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "HEAD"
            setRequestProperty("User-Agent", "TelEnfant")
            connectTimeout = 20000
            readTimeout = 20000
        }
        val code = conn.responseCode
        val location = conn.getHeaderField("Location") ?: ""
        conn.disconnect()
        if (code !in 300..399) throw IllegalStateException("réponse GitHub $code")
        return location.substringAfterLast("/").filter { it.isDigit() }.toLongOrNull()
            ?: throw IllegalStateException("version introuvable")
    }

    fun check(c: Context, silent: Boolean, report: (String) -> Unit) {
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun say(s: String) = main.post { report(s) }
        Thread {
            try {
                val remote = latestVersion()
                val current = currentVersion(app)
                if (remote <= current) {
                    say("L'appli est à jour (version $current).")
                    return@Thread
                }
                say("Téléchargement de la version $remote…")
                val file = File(app.cacheDir, "update.apk")
                download(APK, file)
                install(app, file)
                say("Installation de la version $remote… L'appli va redémarrer.")
            } catch (e: java.net.UnknownHostException) {
                if (!silent) say("Pas de connexion internet (vérifiez les données mobiles).")
            } catch (e: Exception) {
                if (!silent) say("Mise à jour impossible : ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    fun download(url: String, dest: File) {
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

    private fun install(c: Context, apk: File) = installApk(c, apk, c.packageName)

    /** Installation silencieuse d'un APK (mode propriétaire de l'appareil). */
    fun installApk(c: Context, apk: File, packageName: String? = null) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (packageName != null) setAppPackageName(packageName)
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
