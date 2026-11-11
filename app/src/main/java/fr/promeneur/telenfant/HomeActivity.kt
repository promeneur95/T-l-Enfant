package fr.promeneur.telenfant

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Écran d'accueil de l'enfant : contacts, SOS, applis autorisées, thèmes, écran de veille. */
class HomeActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var veille: LinearLayout
    private val clocks = mutableListOf<TextView>()
    private val dates = mutableListOf<TextView>()
    private val batteries = mutableListOf<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var taps = 0
    private var lastTap = 0L
    private val rolesAsked = mutableSetOf<String>()
    private var registered = false

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000)
        }
    }

    private val smsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = buildContent()
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Store.mode(context) != "parent") showVeille(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Policy.isOwner(this)) Store.setMode(this, "child")
        when (Store.mode(this)) {
            "parent" -> { openParent(); return }
            "" -> { chooseMode(); return }
        }
        startChild()
    }

    private fun startChild() {
        Policy.apply(this)
        TrackingService.sync(this)

        root = FrameLayout(this)
        content = Ui.vertical(this, 0)
        veille = Ui.vertical(this, 24)
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(veille, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        buildContent()
        buildVeille()
        showVeille(false)

        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, f)
        registered = true

        if (!Store.hasPin(this)) Ui.createPin(this, cancellable = false) { buildContent() }
    }

    override fun onDestroy() {
        if (registered) try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- Choix du mode (première installation sans QR code) ----------
    private fun chooseMode() {
        AlertDialog.Builder(this)
            .setTitle("Ce téléphone est celui…")
            .setItems(arrayOf("👧  de l'enfant (téléphone bridé)", "👨‍👩‍👧  du parent (suivi de la position)")) { _, which ->
                if (which == 0) {
                    Store.setMode(this, "child")
                    startChild()
                    resumeChild()
                } else {
                    Store.setMode(this, "parent")
                    // Ne plus se proposer comme écran d'accueil sur le téléphone du parent
                    try {
                        packageManager.setComponentEnabledSetting(
                            android.content.ComponentName(packageName, "$packageName.HomeAlias"),
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                    } catch (_: Exception) {}
                    openParent()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun openParent() {
        startActivity(Intent(this, ParentActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        resumeChild()
    }

    private fun resumeChild() {
        if (Store.mode(this) != "child" || !::content.isInitialized) return
        Rules.backHome(this)
        Rules.enforce(this)
        enterKiosk()
        buildContent()
        updateClock()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        val f = IntentFilter(SmsStore.ACTION_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsChanged, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(smsChanged, f)
        } catch (_: Exception) {}
        maybeCheckUpdate()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(smsChanged) } catch (_: Exception) {}
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* écran d'accueil : on ne sort pas */ }

    // ---------- Kiosque ----------
    private fun requestMissingRole(): Boolean {
        val rm = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        val role = listOf(android.app.role.RoleManager.ROLE_DIALER, android.app.role.RoleManager.ROLE_SMS)
            .firstOrNull { it !in rolesAsked && rm.isRoleAvailable(it) && !rm.isRoleHeld(it) } ?: return false
        rolesAsked += role
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(role), 42)
            true
        } catch (_: Exception) { false }
    }

    private fun enterKiosk() {
        if (requestMissingRole()) return
        if (!Policy.isOwner(this) || System.currentTimeMillis() < Store.lockSuspendedUntil) return
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask() } catch (_: Exception) {}
        }
    }

    // ---------- Écran principal ----------
    private fun buildContent() {
        if (!::content.isInitialized) return
        val p = Themes.current(this)
        @Suppress("DEPRECATION")
        window.statusBarColor = p.top
        @Suppress("DEPRECATION")
        window.navigationBarColor = p.bottom
        content.removeAllViews()
        content.background = Themes.background(p)
        pruneDetached()

        if (!Policy.isOwner(this)) {
            content.addView(Ui.text(this, "Bridage inactif : appli non propriétaire de l'appareil (installation par QR code requise).", 13f, Color.WHITE).apply {
                setBackgroundColor(Ui.RED)
                setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        // En-tête
        val onTop = Themes.onColor(p.top)
        val header = Ui.vertical(this, 16).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (hour) {
            in 5..11 -> "☀️ Bonjour"
            in 12..17 -> "🌈 Coucou"
            in 18..21 -> "🌆 Bonsoir"
            else -> "🌙 Bonne nuit"
        }
        val name = Store.childName(this)
        header.addView(Ui.text(this, if (name.isBlank()) "$hello !" else "$hello $name !", 22f, onTop, true).apply {
            gravity = Gravity.CENTER
        })
        val clock = Ui.text(this, "", 64f, onTop, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setOnClickListener { onClockTap() }
        }
        clocks += clock
        header.addView(clock)
        val date = Ui.text(this, "", 18f, onTop).apply { gravity = Gravity.CENTER }
        dates += date
        header.addView(date)
        val chips = Ui.horizontal(this).apply { gravity = Gravity.CENTER }
        val bat = chip(p, "")
        batteries += bat
        chips.addView(bat)
        if (Store.trackingEnabled(this)) chips.addView(chip(p, "📍 Position partagée"))
        header.addView(chips)
        content.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Raccourcis : lampe et sonnerie
        val quick = Ui.horizontal(this).apply { setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), Ui.dp(context, 4)) }
        Torch.watch(this) { runOnUiThread { buildContent() } }
        if (Torch.available(this)) {
            val torch = Ui.button(this, if (Torch.isOn) "🔦 Allumée" else "🔦 Lampe",
                if (Torch.isOn) 0xFFFFC107.toInt() else 0x33000000, 16f) {
                if (!Torch.toggle(this)) toast("Lampe indisponible")
                buildContent()
            }
            if (Torch.isOn) torch.setTextColor(Ui.TEXT)
            quick.addView(torch, LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f).apply { marginEnd = Ui.dp(this@HomeActivity, 6) })
        }
        CallManager.voicemail(this)?.let { vm ->
            quick.addView(Ui.button(this, "📼 Répondeur", 0x33000000, 16f) { CallManager.dial(this, vm) },
                LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f).apply {
                    marginStart = Ui.dp(this@HomeActivity, 6); marginEnd = Ui.dp(this@HomeActivity, 6)
                })
        }
        val vib = Sound.isVibrate(this)
        quick.addView(Ui.button(this, if (vib) "📳 Vibreur" else "🔔 Sonnerie", if (vib) 0xFF6A1B9A.toInt() else 0x33000000, 16f) {
            if (!Sound.setVibrate(this, !Sound.isVibrate(this))) toast("Impossible de changer le mode")
            else toast(if (Sound.isVibrate(this)) "Mode vibreur 📳" else "Sonnerie activée 🔔")
            buildContent()
        }, LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f).apply { marginStart = Ui.dp(this@HomeActivity, 6) })
        content.addView(quick, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Contacts + applis
        val body = Ui.vertical(this, 12)
        Rules.banner(this)?.let { b ->
            body.addView(Ui.text(this, b, 16f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = Ui.rounded(0xCC37474F.toInt(), Ui.dp(context, 16).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = Ui.dp(this@HomeActivity, 8) })
        }
        val contacts = Store.contacts(this).filter { Rules.contactAllowedNow(this, it) }
        if (contacts.isEmpty()) {
            body.addView(Ui.text(this, "🧸\nAucun contact pour l'instant.\nDemande à un parent de les ajouter.", 18f, Ui.TEXT).apply {
                gravity = Gravity.CENTER
                background = Ui.rounded(0xCCFFFFFF.toInt(), Ui.dp(context, 20).toFloat())
                setPadding(Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 16), Ui.dp(context, 24))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val unread = SmsStore.unreadByKey(this)
        contacts.chunked(2).forEachIndexed { row, pair ->
            val line = Ui.horizontal(this)
            pair.forEachIndexed { col, c ->
                val color = p.cards[(row * 2 + col) % p.cards.size]
                line.addView(card(c, color, p, unread[Store.key(c.number)] ?: 0),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        val m = Ui.dp(this@HomeActivity, 6)
                        setMargins(m, m, m, m)
                    })
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            body.addView(line)
        }
        addApps(body, p)
        content.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Barre du bas : SOS, 112, thèmes
        val bar = Ui.horizontal(this).apply {
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 12))
        }
        val sos = roundButton("🆘\nSOS", Ui.RED, 76)
        Hold.attach(sos, 2000, { toast("Garde le doigt appuyé 2 secondes pour envoyer le SOS") }) { launchSos() }
        bar.addView(sos, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)))
        val urgences = Ui.button(this, "🚑  Urgences 112", 0xFFB71C1C.toInt(), 17f) {
            AlertDialog.Builder(this)
                .setTitle("Appeler les urgences ?")
                .setMessage("Le 112 est le numéro d'urgence (pompiers, SAMU, police).")
                .setPositiveButton("Appeler") { _, _ -> CallManager.dial(this, "112") }
                .setNegativeButton("Non", null)
                .show()
        }
        bar.addView(urgences, LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1f).apply {
            marginStart = Ui.dp(this@HomeActivity, 10); marginEnd = Ui.dp(this@HomeActivity, 10)
        })
        val theme = roundButton("🎨", Color.WHITE, 64).apply { setOnClickListener { chooseTheme() } }
        bar.addView(theme, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)))
        content.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        updateClock()
    }

    /** Oublie les horloges des vues reconstruites. */
    private fun pruneDetached() {
        fun attached(v: View): Boolean {
            var parent = v.parent
            while (parent != null) {
                if (parent === root) return true
                parent = parent.parent
            }
            return false
        }
        clocks.removeAll { !attached(it) }
        dates.removeAll { !attached(it) }
        batteries.removeAll { !attached(it) }
    }

    private fun chip(p: Palette, text: String) = Ui.text(this, text, 14f, Themes.onColor(p.accent), true).apply {
        background = Ui.rounded(p.accent, Ui.dp(context, 20).toFloat())
        setPadding(Ui.dp(context, 12), Ui.dp(context, 4), Ui.dp(context, 12), Ui.dp(context, 4))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            setMargins(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), 0)
        }
    }

    private fun roundButton(label: String, color: Int, sizeDp: Int) = Button(this).apply {
        text = label
        textSize = if (label.length > 3) 15f else 26f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Themes.onColor(color))
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(Ui.dp(context, 3), Color.WHITE)
        }
        elevation = Ui.dp(context, 6).toFloat()
        minimumWidth = Ui.dp(context, sizeDp); minimumHeight = Ui.dp(context, sizeDp)
    }

    private fun card(c: Contact, color: Int, p: Palette, unread: Int): LinearLayout {
        val card = Ui.vertical(this, 12).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(color, Ui.dp(context, 24).toFloat(), Ui.dp(context, 2), Color.WHITE)
            elevation = Ui.dp(context, 4).toFloat()
        }
        val avatar = TextView(this).apply {
            text = c.emoji
            textSize = 40f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        card.addView(avatar, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)))
        card.addView(Ui.text(this, c.name, 20f, Themes.onColor(color), true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 8))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val row = Ui.horizontal(this)
        val call = Ui.button(this, "📞", p.accent, 22f) { CallManager.dial(this, c.number) }
        val sms = Ui.button(this, if (unread > 0) "💬 $unread" else "💬", if (unread > 0) 0xFFEF6C00.toInt() else p.accent, 22f) {
            startActivity(Intent(this, SmsActivity::class.java).putExtra(SmsActivity.EXTRA_NUMBER, c.number))
        }
        row.addView(call, LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f).apply { marginEnd = Ui.dp(this@HomeActivity, 4) })
        row.addView(sms, LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f).apply { marginStart = Ui.dp(this@HomeActivity, 4) })
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun addApps(body: LinearLayout, p: Palette) {
        if (Rules.mode(this) != Rules.Mode.NORMAL) return
        val pm = packageManager
        val pkgs = Store.allowedApps(this).toMutableList()
        if (Store.cameraEnabled(this)) CameraApp.packageName(this)?.let { if (it !in pkgs) pkgs.add(0, it) }
        val apps = pkgs.mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                Triple(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info)) to launch
            } catch (_: Exception) { null }
        }.sortedBy { it.first.second.lowercase() }
        if (apps.isEmpty()) return
        body.addView(Ui.text(this, "🎮 Mes applis", 20f, Themes.onColor(p.bottom), true).apply {
            setPadding(Ui.dp(context, 8), Ui.dp(context, 16), 0, Ui.dp(context, 4))
        })
        apps.chunked(4).forEach { chunk ->
            val row = Ui.horizontal(this)
            chunk.forEach { (meta, launch) ->
                val cell = Ui.vertical(this, 6).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = Ui.rounded(0xCCFFFFFF.toInt(), Ui.dp(context, 18).toFloat())
                    setOnClickListener {
                        val left = Rules.remainingSeconds(this@HomeActivity, meta.first)
                        if (left != null && left <= 0) {
                            toast("⏳ Plus de temps pour ${meta.second} aujourd'hui. À demain !")
                        } else {
                            Rules.appOpened(this@HomeActivity, meta.first)
                            try { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: Exception) { toast("Impossible d'ouvrir l'appli") }
                        }
                    }
                }
                cell.addView(ImageView(this).apply { setImageDrawable(meta.third) },
                    LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
                cell.addView(Ui.text(this, meta.second, 12f, Ui.TEXT).apply {
                    gravity = Gravity.CENTER; maxLines = 1
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                Rules.remainingSeconds(this, meta.first)?.let { left ->
                    cell.addView(Ui.text(this, if (left > 0) "⏳ ${left / 60} min" else "⏳ fini", 11f,
                        if (left > 0) Ui.GREY else Ui.RED, true).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    if (left <= 0) cell.alpha = 0.5f
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    val m = Ui.dp(this@HomeActivity, 4); setMargins(m, m, m, m)
                })
            }
            repeat(4 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            body.addView(row)
        }
    }

    // ---------- Thèmes (au choix de l'enfant) ----------
    private fun chooseTheme() {
        val box = Ui.vertical(this, 12)
        var dialog: AlertDialog? = null
        Themes.list.chunked(2).forEach { pair ->
            val row = Ui.horizontal(this)
            pair.forEach { pal ->
                val i = Themes.list.indexOf(pal)
                val b = Button(this).apply {
                    text = "${pal.emoji}  ${pal.name}"
                    textSize = 17f
                    isAllCaps = false
                    setTextColor(Themes.onColor(pal.top))
                    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pal.top, pal.accent)).apply {
                        cornerRadius = Ui.dp(context, 16).toFloat()
                    }
                    setOnClickListener { Store.setTheme(this@HomeActivity, i); dialog?.dismiss(); buildContent() }
                }
                row.addView(b, LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f).apply {
                    val m = Ui.dp(this@HomeActivity, 4); setMargins(m, m, m, m)
                })
            }
            box.addView(row)
        }
        box.addView(Ui.text(this, "🎨 Ou choisis ta couleur :", 16f, Ui.TEXT, true).apply {
            setPadding(Ui.dp(context, 4), Ui.dp(context, 12), 0, Ui.dp(context, 4))
        })
        Themes.swatches.chunked(8).forEach { chunk ->
            val row = Ui.horizontal(this)
            chunk.forEach { color ->
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(color); setStroke(Ui.dp(context, 2), Color.WHITE)
                    }
                    setOnClickListener { Store.setCustomColor(this@HomeActivity, color); dialog?.dismiss(); buildContent() }
                }
                row.addView(dot, LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f).apply {
                    val m = Ui.dp(this@HomeActivity, 3); setMargins(m, m, m, m)
                })
            }
            box.addView(row)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Choisis ton thème")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ---------- SOS ----------
    private fun launchSos() {
        val status = Ui.text(this, "Envoi du SOS…", 18f, Ui.TEXT).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 8))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("🆘 SOS")
            .setView(status)
            .setPositiveButton("OK", null)
            .show()
        Sos.send(this) { s ->
            runOnUiThread {
                status.text = if (s.startsWith("SOS envoyé")) "✅ $s\n\nReste où tu es si tu peux, on arrive." else s
                if (!dlg.isShowing) toast(s)
            }
        }
    }

    // ---------- Écran de veille ----------
    private fun buildVeille() {
        val p = Themes.current(this)
        veille.removeAllViews()
        pruneDetached()
        veille.gravity = Gravity.CENTER_HORIZONTAL
        veille.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0D1B2A.toInt(), p.top))
        veille.isClickable = true
        veille.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val clock = Ui.text(this, "", 84f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        clocks += clock
        veille.addView(clock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val date = Ui.text(this, "", 20f, Color.WHITE).apply { gravity = Gravity.CENTER }
        dates += date
        veille.addView(date, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bat = Ui.text(this, "", 16f, 0xFFCFD8DC.toInt()).apply { gravity = Gravity.CENTER }
        batteries += bat
        veille.addView(bat, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        veille.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val sos = roundButton("🆘\nSOS", Ui.RED, 110)
        Hold.attach(sos, 2000, { toast("Garde le doigt appuyé 2 secondes pour envoyer le SOS") }) {
            showVeille(false); launchSos()
        }
        veille.addView(sos, LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 110)))
        veille.addView(Ui.text(this, "En cas de danger, reste appuyé sur SOS", 14f, 0xFFCFD8DC.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 24))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val open = Ui.button(this, "👆  Ouvrir", 0x55FFFFFF, 20f) { showVeille(false) }
        veille.addView(open, LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(this, 64)))
    }

    private fun showVeille(show: Boolean) {
        if (!::veille.isInitialized) return
        if (show) buildVeille()
        veille.visibility = if (show) View.VISIBLE else View.GONE
        updateClock()
    }

    // ---------- Divers ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun onClockTap() {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 1500) taps + 1 else 1
        lastTap = now
        if (taps >= 5) {
            taps = 0
            Ui.askPin(this) { startActivity(Intent(this, AdminActivity::class.java)) }
        }
    }

    private fun updateClock() {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        val day = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() }
        val level = Locator.battery(this)
        val charging = getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
        val batText = (if (charging) "⚡ " else if (level <= 20) "🪫 " else "🔋 ") + "$level %"
        clocks.forEach { it.text = time }
        dates.forEach { it.text = day }
        batteries.forEach { it.text = batText }
    }

    private fun maybeCheckUpdate() {
        if (System.currentTimeMillis() - Store.lastUpdateCheck(this) < 24 * 3600_000L) return
        Store.setLastUpdateCheck(this)
        Updater.check(this, silent = true) { }
    }
}
