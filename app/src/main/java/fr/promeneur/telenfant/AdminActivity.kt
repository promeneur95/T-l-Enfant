package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Menu parent (protégé par le code admin). */
class AdminActivity : Activity() {

    private lateinit var contactsBox: LinearLayout
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.vertical(this, 16).apply { setBackgroundColor(Ui.BG) }

        root.addView(Ui.text(this, "Menu parent", 26f, Ui.GREEN, true))
        info = Ui.text(this, "", 14f, Ui.GREY)
        root.addView(info)
        root.addView(Ui.space(this, 12))

        // --- Contacts
        root.addView(Ui.text(this, "Contacts autorisés", 20f, Ui.TEXT, true))
        contactsBox = Ui.vertical(this, 0)
        root.addView(contactsBox)
        val nameF = EditText(this).apply { hint = "Nom (ex : Papa)"; textSize = 18f }
        val numF = EditText(this).apply {
            hint = "Numéro (ex : 06 12 34 56 78)"; textSize = 18f
            inputType = InputType.TYPE_CLASS_PHONE
        }
        root.addView(nameF)
        root.addView(numF)
        root.addView(Ui.button(this, "➕ Ajouter le contact", Ui.GREEN) {
            val n = nameF.text.toString().trim()
            val num = numF.text.toString().trim()
            if (n.isEmpty() || Store.key(num).length < 9) {
                Toast.makeText(this, "Nom et numéro complet requis", Toast.LENGTH_SHORT).show()
                return@button
            }
            if (Store.find(this, num) != null) {
                Toast.makeText(this, "Ce numéro est déjà dans la liste", Toast.LENGTH_SHORT).show()
                return@button
            }
            Store.saveContacts(this, Store.contacts(this) + Contact(n, num))
            nameF.setText(""); numF.setText("")
            refresh()
        }, full())
        root.addView(Ui.space(this, 20))

        // --- Réglages
        root.addView(Ui.text(this, "Réglages", 20f, Ui.TEXT, true))
        root.addView(Ui.button(this, "🔑 Changer le code admin", Ui.BLUE) {
            Ui.createPin(this, cancellable = true) { toast("Code modifié") }
        }, full())
        root.addView(Ui.button(this, "⬇️ Vérifier les mises à jour", Ui.BLUE) {
            toast("Vérification…")
            Updater.check(this, silent = false) { toast(it) }
        }, full())
        root.addView(Ui.button(this, "⚙️ Ouvrir les Réglages (Wi-Fi, son…) 10 min", Ui.GREY) {
            Store.lockSuspendedUntil = System.currentTimeMillis() + 10 * 60_000L
            try { stopLockTask() } catch (_: Exception) {}
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }, full())
        root.addView(Ui.button(this, "🛡️ Réappliquer la protection", Ui.GREY) {
            Store.lockSuspendedUntil = 0
            Policy.apply(this)
            toast("Protection réappliquée")
            refresh()
        }, full())
        root.addView(Ui.button(this, "Remettre à zéro le compteur de blocages", Ui.GREY) {
            Store.resetBlocked(this); refresh()
        }, full())
        root.addView(Ui.space(this, 20))

        root.addView(Ui.text(this, "Zone sensible", 20f, Ui.RED, true))
        root.addView(Ui.button(this, "⛔ Désactiver définitivement le bridage", Ui.RED) { confirmRelease() }, full())
        root.addView(Ui.space(this, 12))
        root.addView(Ui.button(this, "Fermer", Ui.GREEN) { finish() }, full())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun full() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = Ui.dp(this@AdminActivity, 8)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun refresh() {
        val owner = Policy.isOwner(this)
        info.text = "Version ${Updater.currentVersion(this)} · " +
            (if (owner) "Bridage ACTIF" else "Bridage INACTIF (pas propriétaire)") +
            " · Appels/SMS bloqués : ${Store.blockedCount(this)}"

        contactsBox.removeAllViews()
        val list = Store.contacts(this)
        if (list.isEmpty()) contactsBox.addView(Ui.text(this, "Aucun contact.", 16f, Ui.GREY))
        list.forEach { c ->
            val row = Ui.horizontal(this).apply {
                background = Ui.rounded(Color.WHITE, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
            }
            row.addView(Ui.text(this, "${c.name}\n${c.number}", 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Ui.button(this, "Supprimer", Ui.RED, 14f) {
                AlertDialog.Builder(this)
                    .setMessage("Retirer ${c.name} de la liste ?")
                    .setPositiveButton("Retirer") { _, _ ->
                        Store.saveContacts(this, Store.contacts(this).filter { it != c })
                        refresh()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            })
            contactsBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@AdminActivity, 6)
            })
        }
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Désactiver le bridage ?")
            .setMessage(
                "Le téléphone redeviendra normal : tous les verrous sont levés et l'appli pourra être désinstallée.\n\n" +
                    "Pour remettre le bridage ensuite, il faudra réinitialiser le téléphone et rescanner le QR code."
            )
            .setPositiveButton("Désactiver") { _, _ ->
                try { stopLockTask() } catch (_: Exception) {}
                Policy.release(this)
                toast("Bridage désactivé. Choisissez votre écran d'accueil habituel.")
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
