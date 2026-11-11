package fr.promeneur.telenfant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mode « téléphone parent » : carte de la position du téléphone de l'enfant. */
class ParentActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var last: JSONObject? = null
    private var lastSosAlert = 0L
    private var mapReady = false

    private val poller = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 60_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.vertical(this, 0).apply { setBackgroundColor(Ui.BG) }

        val top = Ui.vertical(this, 12).apply { setBackgroundColor(Ui.BLUE) }
        top.addView(Ui.text(this, "📍 Suivi du téléphone", 22f, Color.WHITE, true))
        info = Ui.text(this, "Chargement…", 15f, Color.WHITE)
        top.addView(info)
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadDataWithBaseURL("https://telenfant.local/", MAP_HTML, "text/html", "utf-8", null)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    mapReady = true
                    refresh()
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bar = Ui.horizontal(this).apply { setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8)) }
        bar.addView(Ui.button(this, "🔄 Actualiser", Ui.GREEN, 16f) { refresh() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@ParentActivity, 4) })
        bar.addView(Ui.button(this, "🧭 Y aller", Ui.BLUE, 16f) { navigate() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@ParentActivity, 4); marginEnd = Ui.dp(this@ParentActivity, 4) })
        bar.addView(Ui.button(this, "⚙️", Ui.GREY, 16f) { askCode(cancellable = true) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(this@ParentActivity, 4) })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)

        if (Store.parentCode(this).isBlank()) askCode(cancellable = false)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    private fun askCode(cancellable: Boolean) {
        val box = Ui.vertical(this, 16)
        box.addView(Ui.text(this, "Saisissez le code de suivi affiché dans le menu parent du téléphone de l'enfant (rubrique Localisation).", 15f))
        val field = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX-XXXX"
            setText(Store.parentCode(this@ParentActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            textSize = 20f
            gravity = Gravity.CENTER
        }
        box.addView(field)
        val b = AlertDialog.Builder(this)
            .setTitle("Code de suivi")
            .setView(box)
            .setPositiveButton("Valider") { _, _ ->
                val code = Tracker.normalize(field.text.toString())
                if (code.length == 16) {
                    Store.setParentCode(this, code)
                    last = null
                    refresh()
                } else {
                    info.text = "Code invalide (16 caractères attendus)."
                    if (!cancellable) askCode(false)
                }
            }
            .setCancelable(cancellable)
        if (cancellable) {
            b.setNegativeButton("Annuler", null)
            b.setNeutralButton("Ce téléphone est celui de l'enfant") { _, _ ->
                Store.setMode(this, "child")
                try {
                    packageManager.setComponentEnabledSetting(
                        android.content.ComponentName(packageName, "$packageName.HomeAlias"),
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
        }
        b.show()
    }

    private fun refresh() {
        val code = Store.parentCode(this)
        if (code.isBlank()) return
        Thread {
            val points = try { Tracker.poll(code, "12h") } catch (e: Exception) { null }
            runOnUiThread { show(points) }
        }.start()
    }

    private fun show(points: List<JSONObject>?) {
        if (points == null) { info.text = "Pas de connexion internet."; return }
        val withPos = points.filter { it.has("lat") }.sortedBy { it.optLong("t") }
        val latest = points.maxByOrNull { it.optLong("t") }
        if (latest == null) {
            info.text = "Aucune position reçue ces 12 dernières heures.\nVérifiez que le suivi est activé sur le téléphone de l'enfant."
            return
        }
        last = withPos.lastOrNull()
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)
        val name = latest.optString("name").ifBlank { "Enfant" }
        val ago = (System.currentTimeMillis() - latest.optLong("t")) / 60_000
        info.text = "$name · vu il y a ${if (ago < 1) "moins d'1" else ago.toString()} min (${fmt.format(Date(latest.optLong("t")))})" +
            " · 🔋 ${latest.optInt("bat")} %" +
            (last?.let { " · précision ${it.optDouble("acc").toInt()} m" } ?: "")

        val arr = JSONArray()
        withPos.forEach {
            arr.put(JSONObject().put("lat", it.optDouble("lat")).put("lon", it.optDouble("lon"))
                .put("acc", it.optDouble("acc")).put("sos", it.optBoolean("sos"))
                .put("label", fmt.format(Date(it.optLong("t")))))
        }
        if (mapReady) web.evaluateJavascript("show(${arr});", null)

        val sos = points.filter { it.optBoolean("sos") }.maxByOrNull { it.optLong("t") }
        if (sos != null && sos.optLong("t") > lastSosAlert && System.currentTimeMillis() - sos.optLong("t") < 3 * 3600_000L) {
            lastSosAlert = sos.optLong("t")
            Hold.vibrate(this, 1000)
            AlertDialog.Builder(this)
                .setTitle("🆘 SOS reçu")
                .setMessage("$name a déclenché un SOS à ${fmt.format(Date(sos.optLong("t")))}.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun navigate() {
        val p = last ?: return
        val uri = Uri.parse("geo:${p.optDouble("lat")},${p.optDouble("lon")}?q=${p.optDouble("lat")},${p.optDouble("lon")}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
    }

    companion object {
        private const val MAP_HTML = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{margin:0;height:100%}</style></head><body><div id="m"></div><script>
var map=L.map('m').setView([46.6,2.4],5);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
var layer=L.layerGroup().addTo(map);
function show(pts){layer.clearLayers();if(!pts.length)return;
var line=pts.map(function(p){return [p.lat,p.lon]});
L.polyline(line,{color:'#1565C0',weight:3,opacity:.5}).addTo(layer);
pts.forEach(function(p,i){var last=i==pts.length-1;
var c=p.sos?'#C62828':(last?'#2E7D32':'#1565C0');
L.circleMarker([p.lat,p.lon],{radius:last?10:5,color:c,fillColor:c,fillOpacity:last?.9:.5}).bindPopup((p.sos?'SOS · ':'')+p.label).addTo(layer);
if(last){L.circle([p.lat,p.lon],{radius:p.acc,color:c,weight:1,fillOpacity:.1}).addTo(layer);map.setView([p.lat,p.lon],16);}});}
</script></body></html>"""
    }
}
