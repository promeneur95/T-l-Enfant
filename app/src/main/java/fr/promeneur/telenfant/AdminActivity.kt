package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Menu parent (protégé par le code admin). Le kiosque est suspendu tant que ce menu est ouvert. */
class AdminActivity : Activity() {

    companion object { const val PLAY_STORE = "com.android.vending" }

    private lateinit var contactsBox: LinearLayout
    private lateinit var info: TextView
    private lateinit var codeView: TextView
    private lateinit var voicemailBtn: Button
    private var newEmoji = "🙂"

    private val emojis = listOf(
        "👨", "👩", "👴", "👵", "🧔", "👱‍♀️", "👦", "👧", "👶", "🧑‍🏫", "👮", "🧑‍⚕️",
        "🦁", "🐻", "🐼", "🐶", "🐱", "🦊", "🐸", "🐵", "🦄", "🐲", "⭐", "❤️",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenOff.closeOnScreenOff(this)
        val root = Ui.vertical(this, 16).apply { setBackgroundColor(Ui.BG) }

        root.addView(Ui.text(this, "Menu parent", 26f, Ui.GREEN, true))
        info = Ui.text(this, "", 14f, Ui.GREY)
        root.addView(info)

        // --- Enfant
        section(root, "👧 Enfant")
        val nameF = EditText(this).apply {
            hint = "Prénom (affiché à l'accueil et dans les SOS)"
            setText(Store.childName(this@AdminActivity)); textSize = 18f
        }
        root.addView(nameF)
        root.addView(Ui.button(this, "Enregistrer le prénom", Ui.GREY) {
            Store.setChildName(this, nameF.text.toString().trim()); toast("Prénom enregistré")
        }, full())

        // --- Contacts
        section(root, "📒 Contacts autorisés")
        root.addView(Ui.text(this, "« SOS » : ce contact reçoit les alertes SOS avec la position. Sans contact SOS, tous les contacts sont prévenus.", 13f, Ui.GREY))
        contactsBox = Ui.vertical(this, 0)
        root.addView(contactsBox)
        val nameC = EditText(this).apply { hint = "Nom (ex : Papa)"; textSize = 18f }
        val numF = EditText(this).apply {
            hint = "Numéro (ex : 06 12 34 56 78)"; textSize = 18f
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val emojiBtn = Button(this).apply { text = newEmoji; textSize = 26f }
        emojiBtn.setOnClickListener { pickEmoji { e -> newEmoji = e; emojiBtn.text = e } }
        val line = Ui.horizontal(this)
        line.addView(emojiBtn, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)))
        val fields = Ui.vertical(this, 0).apply { addView(nameC); addView(numF) }
        line.addView(fields, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(line)
        @Suppress("DEPRECATION")
        val sosSw = Switch(this).apply { text = "Contact SOS"; textSize = 16f }
        root.addView(sosSw)
        root.addView(Ui.button(this, "➕ Ajouter le contact", Ui.GREEN) {
            val n = nameC.text.toString().trim()
            val num = numF.text.toString().trim()
            if (n.isEmpty() || Store.key(num).length < 9) {
                toast("Nom et numéro complet requis"); return@button
            }
            if (Store.find(this, num) != null) {
                toast("Ce numéro est déjà dans la liste"); return@button
            }
            Store.saveContacts(this, Store.contacts(this) + Contact(n, num, newEmoji, sosSw.isChecked))
            nameC.setText(""); numF.setText(""); sosSw.isChecked = false
            newEmoji = "🙂"; emojiBtn.text = newEmoji
            refresh()
        }, full())

        // --- Localisation
        section(root, "📍 Localisation")
        @Suppress("DEPRECATION")
        val trackSw = Switch(this).apply {
            text = "Partager la position en permanence"
            textSize = 16f
            isChecked = Store.trackingEnabled(this@AdminActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setTrackingEnabled(this@AdminActivity, on)
                TrackingService.sync(this@AdminActivity)
                refresh()
            }
        }
        root.addView(trackSw)
        codeView = Ui.text(this, "", 22f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(Color.WHITE, Ui.dp(context, 12).toFloat())
            setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 12))
            setTextIsSelectable(true)
        }
        root.addView(codeView, full())
        root.addView(Ui.text(this, "Sur le téléphone du parent : installer Tél Enfant (même APK), choisir « Téléphone du parent » puis saisir ce code. La position est chiffrée de bout en bout.", 13f, Ui.GREY))
        root.addView(Ui.button(this, "🔁 Générer un nouveau code (l'ancien ne marchera plus)", Ui.GREY, 14f) {
            AlertDialog.Builder(this)
                .setMessage("Changer le code ? Il faudra saisir le nouveau sur le téléphone parent.")
                .setPositiveButton("Changer") { _, _ -> Store.renewTrackingCode(this); refresh() }
                .setNegativeButton("Annuler", null).show()
        }, full())

        // --- Applications
        section(root, "🎮 Applications")
        root.addView(Ui.button(this, "Choisir les applis autorisées / installer une appli", Ui.BLUE) {
            startActivity(Intent(this, AppsActivity::class.java))
        }, full())
        root.addView(Ui.text(this, "Google Play : ajoutez un compte Google (une seule fois), installez les applis depuis le Play Store, puis cochez-les dans « Choisir les applis autorisées ». L'enfant n'a jamais accès au Play Store.", 13f, Ui.GREY).apply {
            setPadding(0, Ui.dp(context, 8), 0, 0)
        })
        root.addView(Ui.button(this, "1. Ajouter un compte Google", Ui.BLUE) { addGoogleAccount() }, full())
        root.addView(Ui.button(this, "2. Ouvrir Google Play", Ui.BLUE) { openPlayStore() }, full())

        // --- Son et appareil photo
        section(root, "🔔 Son et 📷 appareil photo")
        @Suppress("DEPRECATION")
        val vibSw = Switch(this).apply {
            text = "Vibreur au démarrage du téléphone (sinon sonnerie)"
            textSize = 16f
            isChecked = Store.defaultVibrate(this@AdminActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setDefaultVibrate(this@AdminActivity, on)
                Sound.setVibrate(this@AdminActivity, on)
            }
        }
        root.addView(vibSw)
        root.addView(Ui.text(this, "L'enfant peut toujours basculer entre sonnerie et vibreur depuis l'accueil (jamais en silence total).", 13f, Ui.GREY))
        @Suppress("DEPRECATION")
        val camSw = Switch(this).apply {
            text = "Appli Appareil photo sur l'accueil"
            textSize = 16f
            isChecked = Store.cameraEnabled(this@AdminActivity)
            setOnCheckedChangeListener { _, on -> Store.setCameraEnabled(this@AdminActivity, on) }
        }
        root.addView(camSw)
        val detected = try {
            getSystemService(android.telephony.TelephonyManager::class.java)?.voiceMailNumber ?: ""
        } catch (_: Exception) { "" }
        val vmF = EditText(this).apply {
            hint = "Numéro du répondeur" + if (detected.isNotBlank()) " (détecté : $detected)" else " (ex : 888, 123, 660, 666)"
            setText(Store.voicemailOverride(this@AdminActivity))
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 16f
        }
        root.addView(vmF)
        root.addView(Ui.button(this, "Enregistrer le numéro du répondeur", Ui.GREY) {
            Store.setVoicemailOverride(this, vmF.text.toString().trim())
            toast(if (CallManager.voicemail(this) != null) "Bouton Répondeur affiché sur l'accueil" else "Aucun numéro de répondeur")
        }, full())
        root.addView(Ui.text(this, "Répondeur chez l'opérateur : les appels rejetés des numéros inconnus y arrivent. Le couper évite qu'un inconnu laisse un message.", 13f, Ui.GREY).apply {
            setPadding(0, Ui.dp(context, 8), 0, 0)
        })
        voicemailBtn = Ui.button(this, "", Ui.RED) { toggleVoicemail() }
        root.addView(voicemailBtn, full())
        root.addView(Ui.text(this, "La galerie reste bloquée : les photos se consultent et s'envoient depuis les messages.", 13f, Ui.GREY))

        // --- Réglages
        section(root, "⚙️ Réglages")
        root.addView(Ui.button(this, "🔑 Changer le code admin", Ui.BLUE) {
            Ui.createPin(this, cancellable = true) { toast("Code modifié") }
        }, full())
        root.addView(Ui.button(this, "⬇️ Vérifier les mises à jour", Ui.BLUE) {
            toast("Vérification…")
            Updater.check(this, silent = false) { toast(it) }
        }, full())
        root.addView(Ui.button(this, "⚙️ Ouvrir les Réglages (Wi-Fi, son…) 10 min", Ui.GREY) {
            Store.lockSuspendedUntil = System.currentTimeMillis() + 10 * 60_000L
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

        section(root, "⛔ Zone sensible")
        root.addView(Ui.button(this, "Désactiver définitivement le bridage", Ui.RED) { confirmRelease() }, full())
        root.addView(Ui.space(this, 12))
        root.addView(Ui.button(this, "Fermer", Ui.GREEN) { finish() }, full())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Parent présent : on sort du kiosque (sélecteurs de fichiers, Réglages…). L'accueil le remet en place.
        try { stopLockTask() } catch (_: Exception) {}
        refresh()
    }

    override fun onDestroy() {
        ScreenOff.release(this)
        super.onDestroy()
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(Ui.space(this, 20))
        root.addView(Ui.text(this, title, 20f, Ui.TEXT, true))
    }

    private fun full() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = Ui.dp(this@AdminActivity, 8)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun addGoogleAccount() {
        AlertDialog.Builder(this)
            .setTitle("Ajouter un compte Google")
            .setMessage(
                "Important : une fois un compte Google ajouté, la protection antivol de Google s'active. " +
                    "En cas de réinitialisation (code admin oublié, réinstallation…), le téléphone demandera ce compte et son mot de passe.\n\n" +
                    "Utilisez de préférence un compte créé pour l'enfant ou pour ce téléphone, et notez ses identifiants."
            )
            .setPositiveButton("Continuer") { _, _ ->
                if (Policy.isOwner(this)) {
                    try {
                        Policy.dpm(this).clearUserRestriction(Policy.admin(this), android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS)
                    } catch (_: Exception) {}
                }
                Store.lockSuspendedUntil = System.currentTimeMillis() + 10 * 60_000L
                try {
                    startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openPlayStore() {
        if (Policy.isOwner(this)) {
            try { Policy.dpm(this).enableSystemApp(Policy.admin(this), PLAY_STORE) } catch (_: Exception) {}
        }
        val launch = packageManager.getLaunchIntentForPackage(PLAY_STORE)
        if (launch == null) { toast("Google Play n'est pas disponible sur ce téléphone"); return }
        Store.lockSuspendedUntil = System.currentTimeMillis() + 10 * 60_000L
        startActivity(launch)
    }

    /** Coupe ou rétablit le répondeur via les codes standards de renvoi d'appel (GSM). */
    private fun toggleVoicemail() {
        val off = Store.voicemailDisabled(this)
        if (!off) {
            AlertDialog.Builder(this)
                .setTitle("Couper le répondeur ?")
                .setMessage("Le téléphone envoie le code ##002# à l'opérateur : tous les renvois d'appel sont annulés, donc plus de répondeur. " +
                    "Les appels rejetés sonneront simplement « occupé ». Une confirmation de l'opérateur s'affiche ensuite.")
                .setPositiveButton("Couper") { _, _ ->
                    CallManager.sendOperatorCode(this, "##002#")
                    Store.setVoicemailDisabled(this, true)
                    refresh()
                }
                .setNegativeButton("Annuler", null)
                .show()
        } else {
            val vm = CallManager.voicemailNumber(this)
            val field = EditText(this).apply {
                hint = "Numéro de renvoi du répondeur"
                inputType = InputType.TYPE_CLASS_PHONE
                setText(vm ?: "")
            }
            AlertDialog.Builder(this)
                .setTitle("Rétablir le répondeur ?")
                .setMessage("Les appels non répondus, occupés ou injoignables seront renvoyés vers ce numéro (code **004*numéro#). " +
                    "Si l'opérateur demande un numéro long (ex : +33 6 95 00 08 88 chez Orange), consultez son site.")
                .setView(field)
                .setPositiveButton("Rétablir") { _, _ ->
                    val n = field.text.toString().filter { it.isDigit() || it == '+' }
                    if (n.isNotEmpty()) CallManager.sendOperatorCode(this, "**004*$n#")
                    Store.setVoicemailDisabled(this, false)
                    refresh()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun pickEmoji(onPick: (String) -> Unit) {
        var dlg: AlertDialog? = null
        val grid = Ui.vertical(this, 8)
        emojis.chunked(6).forEach { chunk ->
            val row = Ui.horizontal(this)
            chunk.forEach { e ->
                row.addView(Button(this).apply {
                    text = e; textSize = 24f
                    setOnClickListener { onPick(e); dlg?.dismiss() }
                }, LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f))
            }
            grid.addView(row)
        }
        dlg = AlertDialog.Builder(this).setTitle("Choisir un avatar").setView(grid).show()
    }

    private fun refresh() {
        val owner = Policy.isOwner(this)
        info.text = "Version ${Updater.currentVersion(this)} · " +
            (if (owner) "Bridage ACTIF" else "Bridage INACTIF (pas propriétaire)") +
            " · Appels/SMS bloqués : ${Store.blockedCount(this)}"
        val vmOff = Store.voicemailDisabled(this)
        voicemailBtn.text = if (vmOff) "📼 Répondeur coupé · Rétablir" else "📵 Couper le répondeur (opérateur)"
        voicemailBtn.background = Ui.rounded(if (vmOff) Ui.GREEN else Ui.RED, Ui.dp(this, 14).toFloat())
        codeView.text = if (Store.trackingEnabled(this)) "Code de suivi\n${Store.trackingCode(this)}" else "Suivi désactivé"

        contactsBox.removeAllViews()
        val list = Store.contacts(this)
        if (list.isEmpty()) contactsBox.addView(Ui.text(this, "Aucun contact.", 16f, Ui.GREY))
        list.forEach { c ->
            val row = Ui.horizontal(this).apply {
                background = Ui.rounded(Color.WHITE, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
            }
            row.addView(Ui.text(this, "${c.emoji} ${c.name}\n${c.number}", 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Ui.button(this, if (c.sos) "🆘 SOS" else "SOS", if (c.sos) Ui.RED else Ui.GREY, 13f) {
                Store.saveContacts(this, Store.contacts(this).map { if (it == c) it.copy(sos = !it.sos) else it })
                refresh()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = Ui.dp(this@AdminActivity, 4) })
            row.addView(Ui.button(this, "✕", Ui.RED, 14f) {
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
                Store.setTrackingEnabled(this, false)
                TrackingService.sync(this)
                Policy.release(this)
                toast("Bridage désactivé. Choisissez votre écran d'accueil habituel.")
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
