package fr.promeneur.telenfant

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran d'accueil de l'enfant : contacts autorisés, appel, SMS, urgences. */
class HomeActivity : Activity() {

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var battery: TextView
    private lateinit var list: LinearLayout
    private lateinit var banner: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var taps = 0
    private var lastTap = 0L
    private val rolesAsked = mutableSetOf<String>()

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000)
        }
    }

    private val smsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshContacts()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Policy.apply(this)

        val root = Ui.vertical(this, 0).apply { setBackgroundColor(Ui.BG) }

        // En-tête : heure (5 appuis rapides = accès parent), date, batterie
        val header = Ui.vertical(this, 20).apply {
            setBackgroundColor(Ui.GREEN)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        clock = Ui.text(this, "", 56f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onClockTap() }
        }
        date = Ui.text(this, "", 18f, Color.WHITE).apply { gravity = Gravity.CENTER }
        battery = Ui.text(this, "", 16f, Color.WHITE).apply { gravity = Gravity.CENTER }
        header.addView(clock)
        header.addView(date)
        header.addView(battery)
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        banner = Ui.text(this, "", 14f, Color.WHITE).apply {
            setBackgroundColor(Ui.RED)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
        }
        root.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        list = Ui.vertical(this, 12)
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val sos = Ui.button(this, "🚨  Urgences (112)", Ui.RED, 20f) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Appeler les urgences ?")
                .setMessage("Le 112 est le numéro d'urgence européen.")
                .setPositiveButton("Appeler") { _, _ -> CallManager.dial(this, "112") }
                .setNegativeButton("Non", null)
                .show()
        }
        val sosBox = Ui.vertical(this, 12).apply { addView(sos, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        root.addView(sosBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        if (!Store.hasPin(this)) {
            Ui.createPin(this, cancellable = false) { refreshContacts() }
        }
    }

    override fun onResume() {
        super.onResume()
        enterKiosk()
        updateClock()
        refreshContacts()
        handler.post(ticker)
        val f = IntentFilter(SmsStore.ACTION_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(smsChanged, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(smsChanged, f)
        maybeCheckUpdate()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(smsChanged) } catch (_: Exception) {}
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* écran d'accueil : on ne sort pas */ }

    /** Si l'attribution automatique a échoué, on demande les rôles Téléphone / SMS (une fois). */
    private fun requestMissingRole(): Boolean {
        val rm = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        val role = listOf(android.app.role.RoleManager.ROLE_DIALER, android.app.role.RoleManager.ROLE_SMS)
            .firstOrNull { it !in rolesAsked && rm.isRoleAvailable(it) && !rm.isRoleHeld(it) } ?: return false
        rolesAsked += role
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(role), 42)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun enterKiosk() {
        if (requestMissingRole()) return
        val owner = Policy.isOwner(this)
        banner.visibility = if (owner) android.view.View.GONE else android.view.View.VISIBLE
        banner.text = "Bridage inactif : l'appli n'est pas propriétaire de l'appareil (installation par QR code requise)."
        if (!owner || System.currentTimeMillis() < Store.lockSuspendedUntil) return
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask() } catch (_: Exception) {}
        }
    }

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
        clock.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        date.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now)
            .replaceFirstChar { it.uppercase() }
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        battery.text = "🔋 $level %" + if (charging) " (en charge)" else ""
    }

    private fun refreshContacts() {
        list.removeAllViews()
        val contacts = Store.contacts(this)
        if (contacts.isEmpty()) {
            list.addView(Ui.text(this, "Aucun contact pour l'instant.\nUn parent doit les ajouter.", 18f, Ui.GREY).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(context, 40), 0, 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        val unread = SmsStore.unreadByKey(this)
        contacts.forEach { c -> list.addView(card(c, unread[Store.key(c.number)] ?: 0)) }
    }

    private fun card(c: Contact, unread: Int): LinearLayout {
        val card = Ui.vertical(this, 14).apply {
            background = Ui.rounded(Color.WHITE, Ui.dp(context, 16).toFloat(), Ui.dp(context, 1), 0xFFDDE5DD.toInt())
        }
        card.addView(Ui.text(this, c.name, 24f, Ui.TEXT, true))
        card.addView(Ui.space(this, 10))
        val row = Ui.horizontal(this)
        val call = Ui.button(this, "📞  Appeler", Ui.GREEN, 18f) { CallManager.dial(this, c.number) }
        val label = if (unread > 0) "💬  Messages ($unread)" else "💬  Messages"
        val sms = Ui.button(this, label, if (unread > 0) 0xFFEF6C00.toInt() else Ui.BLUE, 18f) {
            startActivity(Intent(this, SmsActivity::class.java).putExtra(SmsActivity.EXTRA_NUMBER, c.number))
        }
        row.addView(call, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@HomeActivity, 6) })
        row.addView(sms, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@HomeActivity, 6) })
        card.addView(row)
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(this@HomeActivity, 12)
            }
        }
    }

    private fun maybeCheckUpdate() {
        if (System.currentTimeMillis() - Store.lastUpdateCheck(this) < 24 * 3600_000L) return
        Store.setLastUpdateCheck(this)
        Updater.check(this, silent = true) { }
    }
}
