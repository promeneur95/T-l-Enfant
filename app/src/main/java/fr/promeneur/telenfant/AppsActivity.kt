package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.io.File

/** Menu parent : applications autorisées et installation silencieuse d'applis. */
class AppsActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenOff.closeOnScreenOff(this)
        val root = Ui.vertical(this, 16).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(this, "Applications autorisées", 24f, Ui.GREEN, true))
        root.addView(Ui.text(this, "Les applis cochées apparaissent sur l'écran d'accueil de l'enfant. Toutes les autres restent bloquées.", 14f, Ui.GREY))
        root.addView(Ui.space(this, 8))
        root.addView(Ui.button(this, "⬇️ Installer une appli depuis un lien (APK)", Ui.BLUE) { installFromUrl() }, full())
        root.addView(Ui.button(this, "📁 Installer une appli depuis un fichier (APK)", Ui.BLUE) { installFromFile() }, full())
        root.addView(Ui.space(this, 12))
        list = Ui.vertical(this, 0)
        root.addView(list)
        root.addView(Ui.space(this, 12))
        root.addView(Ui.button(this, "Fermer", Ui.GREEN) { finish() }, full())
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        ScreenOff.release(this)
        super.onDestroy()
    }

    private fun full() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = Ui.dp(this@AppsActivity, 8)
    }

    private fun refresh() {
        list.removeAllViews()
        val pm = packageManager
        val hidden = setOf(packageName) + Policy.mediaPackages(this)
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.distinct().filter { it !in hidden }
            .map { pkg -> pkg to (try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }) }
            .sortedBy { it.second.lowercase() }
        val allowed = Store.allowedApps(this).toMutableSet()
        apps.forEach { (pkg, label) ->
            val row = Ui.horizontal(this).apply {
                background = Ui.rounded(Color.WHITE, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
            }
            row.addView(ImageView(this).apply {
                try { setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) {}
            }, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)).apply { marginEnd = Ui.dp(this@AppsActivity, 10) })
            row.addView(Ui.text(this, label, 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            @Suppress("DEPRECATION")
            val sw = Switch(this).apply {
                isChecked = pkg in allowed
                setOnCheckedChangeListener { _, on ->
                    if (on) allowed += pkg else allowed -= pkg
                    Store.setAllowedApps(this@AppsActivity, allowed.toSet())
                    Policy.apply(this@AppsActivity)
                }
            }
            row.addView(sw)
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@AppsActivity, 6)
            })
        }
    }

    private fun installFromUrl() {
        val field = EditText(this).apply {
            hint = "https://…/appli.apk"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Lien de l'APK")
            .setMessage("L'appli sera installée en arrière-plan, sans confirmation. N'utilisez que des sources de confiance.")
            .setView(field)
            .setPositiveButton("Installer") { _, _ ->
                val url = field.text.toString().trim()
                if (!url.startsWith("https://")) { toast("Lien https:// requis"); return@setPositiveButton }
                toast("Téléchargement…")
                Thread {
                    try {
                        val f = File(cacheDir, "install.apk")
                        Updater.download(url, f)
                        Updater.installApk(this, f)
                        runOnUiThread { toast("Installation en cours… Cochez ensuite l'appli dans la liste.") }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Échec : ${e.message}") }
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun installFromFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 5)
        } catch (_: Exception) { toast("Sélecteur de fichiers indisponible") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != 5 || resultCode != RESULT_OK) return
        toast("Installation en cours…")
        Thread {
            try {
                val f = File(cacheDir, "install.apk")
                contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                Updater.installApk(this, f)
                runOnUiThread { toast("Installation lancée. Cochez ensuite l'appli dans la liste.") }
            } catch (e: Exception) {
                runOnUiThread { toast("Échec : ${e.message}") }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
