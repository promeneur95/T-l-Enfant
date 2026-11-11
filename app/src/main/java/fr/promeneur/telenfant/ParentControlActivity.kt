package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Téléphone parent : pilotage à distance du téléphone de l'enfant. */
class ParentControlActivity : Activity() {

    private lateinit var body: LinearLayout
    private lateinit var head: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var waitingSince = 0L
    private var renderedT = -1L

    private val poller = object : Runnable {
        override fun run() {
            fetch()
            handler.postDelayed(this, if (System.currentTimeMillis() - waitingSince < 3 * 60_000L) 15_000L else 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.vertical(this, 0).apply { setBackgroundColor(Ui.BG) }
        val top = Ui.vertical(this, 12).apply { setBackgroundColor(0xFF6A1B9A.toInt()) }
        top.addView(Ui.text(this, "🎛️ Pilotage à distance", 22f, Color.WHITE, true))
        head = Ui.text(this, "", 14f, Color.WHITE)
        top.addView(head)
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body = Ui.vertical(this, 16)
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
        send(JSONObject().put("cmd", "status"), quiet = true)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    private fun code() = Store.parentCode(this)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Envoie une commande ; le téléphone de l'enfant l'applique dans la minute. */
    private fun send(cmd: JSONObject, quiet: Boolean = false) {
        if (code().isBlank()) { toast("Code de suivi manquant"); return }
        waitingSince = System.currentTimeMillis()
        Thread {
            val ok = Remote.send(code(), cmd)
            runOnUiThread {
                if (!quiet) toast(if (ok) "✅ Envoyé : appliqué sous une minute environ" else "❌ Envoi impossible (connexion ?)")
                head.text = head.text.toString().substringBefore("\n⏳") + if (ok) "\n⏳ Mise à jour demandée au téléphone…" else ""
            }
        }.start()
    }

    private fun fetch() {
        if (code().isBlank()) return
        Thread {
            val points = try { Tracker.poll(code(), "12h") } catch (e: Exception) { null }
            runOnUiThread {
                if (points != null) ParentJob.check(this, points)
                // On ne reconstruit l'écran que si un nouvel état est arrivé (évite d'effacer une saisie)
                val t = ParentStore.lastStatus(this)?.optLong("t") ?: 0L
                if (t != renderedT) render()
            }
        }.start()
    }

    private fun section(title: String) {
        body.addView(Ui.space(this, 16))
        body.addView(Ui.text(this, title, 19f, Ui.TEXT, true))
    }

    private fun full() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@ParentControlActivity, 6) }

    private fun render() {
        body.removeAllViews()
        val st = ParentStore.lastStatus(this)
        renderedT = st?.optLong("t") ?: 0L
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)
        head.text = if (st == null) "En attente du téléphone de l'enfant (le suivi doit y être activé)…"
        else "${st.optString("name").ifBlank { "Enfant" }} · état du ${fmt.format(Date(st.optLong("t")))} · 🔋 ${st.optInt("bat")} % · v${st.optLong("ver")}" +
            when (st.optString("mode")) { "SCHOOL" -> " · 🏫 école"; "NIGHT" -> " · 🌙 nuit"; else -> "" }

        // Actions immédiates
        val row = Ui.horizontal(this)
        row.addView(Ui.button(this, "🔔 Faire sonner", Ui.RED, 16f) {
            AlertDialog.Builder(this).setMessage("Faire sonner fort le téléphone pendant 30 secondes (même en vibreur) ?")
                .setPositiveButton("Sonner") { _, _ -> send(JSONObject().put("cmd", "ring")) }
                .setNegativeButton("Annuler", null).show()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@ParentControlActivity, 4) })
        row.addView(Ui.button(this, "📍 Localiser", Ui.BLUE, 16f) { send(JSONObject().put("cmd", "locate")) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@ParentControlActivity, 4) })
        body.addView(row)
        body.addView(Ui.button(this, "🔄 Rafraîchir l'état du téléphone", Ui.GREY, 15f) {
            send(JSONObject().put("cmd", "status"))
        }, full())

        if (st == null) return

        // Contacts
        section("📒 Contacts")
        val contacts = st.optJSONArray("contacts")
        for (i in 0 until (contacts?.length() ?: 0)) {
            val c = contacts!!.getJSONObject(i)
            val line = Ui.horizontal(this).apply {
                background = Ui.rounded(Color.WHITE, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
            }
            line.addView(Ui.text(this, "${c.optString("e")} ${c.optString("n")}\n${c.optString("num")}", 15f),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val sos = c.optBoolean("s")
            line.addView(Ui.button(this, if (sos) "🆘 SOS" else "SOS", if (sos) Ui.RED else Ui.GREY, 12f) {
                send(JSONObject().put("cmd", "contact_sos").put("number", c.optString("num")).put("sos", !sos))
            })
            line.addView(Ui.button(this, "✕", Ui.RED, 13f) {
                AlertDialog.Builder(this).setMessage("Retirer ${c.optString("n")} ?")
                    .setPositiveButton("Retirer") { _, _ -> send(JSONObject().put("cmd", "contact_del").put("number", c.optString("num"))) }
                    .setNegativeButton("Annuler", null).show()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(this@ParentControlActivity, 4) })
            body.addView(line, full())
        }
        val nameF = EditText(this).apply { hint = "Nom du nouveau contact" }
        val numF = EditText(this).apply { hint = "Numéro"; inputType = InputType.TYPE_CLASS_PHONE }
        @Suppress("DEPRECATION")
        val sosSw = Switch(this).apply { text = "Contact SOS" }
        body.addView(nameF); body.addView(numF); body.addView(sosSw)
        body.addView(Ui.button(this, "➕ Ajouter ce contact", Ui.GREEN, 15f) {
            val n = nameF.text.toString().trim()
            val num = numF.text.toString().trim()
            if (n.isEmpty() || Store.key(num).length < 9) { toast("Nom et numéro complet requis"); return@button }
            send(JSONObject().put("cmd", "contact_add").put("name", n).put("number", num).put("sos", sosSw.isChecked))
        }, full())

        // Applications
        section("🎮 Applications")
        @Suppress("DEPRECATION")
        val cam = Switch(this).apply {
            text = "📷 Appareil photo sur l'accueil"
            isChecked = st.optBoolean("cam")
            setOnCheckedChangeListener { _, on -> send(JSONObject().put("cmd", "camera").put("on", on)) }
        }
        body.addView(cam)
        val apps = st.optJSONArray("apps")
        for (i in 0 until (apps?.length() ?: 0)) {
            val a = apps!!.getJSONObject(i)
            val line = Ui.horizontal(this).apply {
                background = Ui.rounded(Color.WHITE, Ui.dp(context, 10).toFloat())
                setPadding(Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4))
            }
            val lim = a.optInt("m")
            line.addView(Ui.text(this, a.optString("l") + if (lim > 0) "\n⏳ ${a.optInt("u")} / $lim min" else "", 15f),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            line.addView(Ui.button(this, "⏳", Ui.GREY, 13f) { askLimit(a) })
            @Suppress("DEPRECATION")
            val sw = Switch(this).apply {
                isChecked = a.optBoolean("a")
                setOnCheckedChangeListener { _, on ->
                    send(JSONObject().put("cmd", "app").put("pkg", a.optString("p")).put("allow", on))
                }
            }
            line.addView(sw)
            body.addView(line, full())
        }

        // Journal
        section("🚫 Bloqués récemment")
        val j = st.optJSONArray("journal")
        if (j == null || j.length() == 0) body.addView(Ui.text(this, "Rien de bloqué.", 14f, Ui.GREY))
        else {
            val f2 = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
            for (i in j.length() - 1 downTo 0) {
                val o = j.getJSONObject(i)
                val icon = when (o.optString("k")) { "appel" -> "📞"; "sms" -> "💬"; "photo" -> "🖼️"; else -> "•" }
                val x = o.optString("x").let { if (it.isNotBlank()) "\n« $it »" else "" }
                body.addView(Ui.text(this, "$icon ${f2.format(Date(o.optLong("t")))} · ${o.optString("n")} (${o.optString("r")})$x", 14f).apply {
                    setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
                })
            }
        }

        // Zones
        section("📍 Zones")
        val zones = ParentStore.zones(this)
        body.addView(Ui.text(this, if (zones.isEmpty()) "Aucune zone : faites un appui long sur la carte pour en créer (maison, école…)."
            else zones.joinToString("\n") { "• ${it.name} (${it.radius} m)" } + "\nGestion : touchez le bandeau bleu de la carte.", 14f, Ui.GREY))
    }

    private fun askLimit(a: JSONObject) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes par jour (0 = illimité)"
            if (a.optInt("m") > 0) setText(a.optInt("m").toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Temps d'écran : ${a.optString("l")}")
            .setView(field)
            .setPositiveButton("Envoyer") { _, _ ->
                send(JSONObject().put("cmd", "limit").put("pkg", a.optString("p")).put("minutes", field.text.toString().toIntOrNull() ?: 0))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
