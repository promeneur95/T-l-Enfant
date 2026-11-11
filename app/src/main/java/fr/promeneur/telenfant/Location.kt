package fr.promeneur.telenfant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Localisation du téléphone. */
object Locator {
    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Location? {
        if (!hasPermission(c)) return null
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        return lm.getProviders(true).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
    }

    /** Position actuelle (meilleure obtenue avant le délai), sinon dernière connue. */
    @SuppressLint("MissingPermission")
    fun current(c: Context, timeoutMs: Long, cb: (Location?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (!hasPermission(c)) { main.post { cb(null) }; return }
        val lm = c.getSystemService(LocationManager::class.java)
        var best: Location? = null
        var done = false
        fun finish() {
            if (done) return
            done = true
            cb(best ?: lastKnown(c))
        }
        if (lm != null && Build.VERSION.SDK_INT >= 30) {
            val providers = lm.getProviders(true)
            providers.forEach { p ->
                try {
                    lm.getCurrentLocation(p, CancellationSignal(), c.mainExecutor) { loc ->
                        if (loc != null && (best == null || loc.accuracy < best!!.accuracy)) best = loc
                        if (loc != null && loc.accuracy <= 50f) finish()
                    }
                } catch (_: Exception) {}
            }
            if (providers.isEmpty()) { main.post { finish() }; return }
        } else {
            main.post { finish() }
            return
        }
        main.postDelayed({ finish() }, timeoutMs)
    }

    @Suppress("DEPRECATION")
    fun address(c: Context, loc: Location): String? = try {
        Geocoder(c, Locale.FRANCE).getFromLocation(loc.latitude, loc.longitude, 1)
            ?.firstOrNull()?.getAddressLine(0)
    } catch (_: Exception) { null }

    fun mapsLink(loc: Location) =
        "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, loc.latitude, loc.longitude)

    fun battery(c: Context): Int =
        c.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
}

/** Alerte SOS : SMS avec position et adresse aux contacts SOS. */
object Sos {
    fun targets(c: Context): List<Contact> {
        val all = Store.contacts(c)
        return all.filter { it.sos }.ifEmpty { all }
    }

    fun send(c: Context, onStatus: (String) -> Unit) {
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        val targets = targets(app)
        if (targets.isEmpty()) { onStatus("Aucun contact à prévenir"); return }
        onStatus("Recherche de ta position…")
        // Un premier SMS part tout de suite, la position suit dès qu'elle est trouvée
        Thread { targets.forEach { SmsStore.send(app, it.number, "SOS de ${name(app)} ! Envoi de ma position en cours...") } }.start()
        Locator.current(app, 30_000) { loc ->
            Thread {
                val text = buildString {
                    append("SOS de ${name(app)} !")
                    if (loc != null) {
                        append("\nPosition : ").append(Locator.mapsLink(loc))
                        append(" (precision ${loc.accuracy.toInt()} m)")
                        Locator.address(app, loc)?.let { append("\nAdresse : ").append(it) }
                    } else {
                        append("\nPosition introuvable (GPS indisponible).")
                    }
                    append("\nBatterie : ${Locator.battery(app)} %")
                    append("\nHeure : ").append(SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date()))
                }
                targets.forEach { SmsStore.send(app, it.number, text) }
                Tracker.publish(app, loc, sos = true)
                main.post { onStatus("SOS envoyé à " + targets.joinToString(", ") { it.name }) }
            }.start()
        }
    }

    private fun name(c: Context) = Store.childName(c).ifBlank { "ton enfant" }
}

/**
 * Partage de position chiffré via le service public ntfy.sh.
 * Le « code de suivi » est le seul secret : il donne le canal et la clé de chiffrement (AES-GCM).
 */
object Tracker {
    private const val SERVER = "https://ntfy.sh"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun newCode(): String {
        val r = SecureRandom()
        return (1..16).map { ALPHABET[r.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    fun normalize(code: String) = code.uppercase().filter { it in ALPHABET }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    fun topic(code: String) = "te" + sha("topic:" + normalize(code)).joinToString("") { "%02x".format(it) }.take(30)

    private fun key(code: String) = SecretKeySpec(sha("key:" + normalize(code)), "AES")

    fun encrypt(code: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(code: String, b64: String): String? = try {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(cipher.doFinal(all.copyOfRange(12, all.size)))
    } catch (_: Exception) { null }

    fun encryptBytes(code: String, data: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    fun decryptBytes(code: String, all: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return cipher.doFinal(all.copyOfRange(12, all.size))
    }

    /** Dépose un fichier chiffré (pièce jointe ntfy, conservée quelques heures) et renvoie son lien. */
    fun uploadFile(code: String, data: ByteArray): String? = try {
        val conn = (URL("$SERVER/${topic(code)}f").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("Filename", "f.bin")
            setFixedLengthStreamingMode(data.size + 28)
        }
        conn.outputStream.use { it.write(encryptBytes(code, data)) }
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        JSONObject(resp).optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    fun downloadFile(code: String, url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
        }
        return decryptBytes(code, conn.inputStream.use { it.readBytes() })
    }

    /** Envoie un message chiffré sur un canal (à appeler hors du fil principal). */
    fun post(code: String, topic: String, json: JSONObject): Boolean = try {
        val conn = (URL("$SERVER/$topic").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "text/plain")
        }
        conn.outputStream.use { it.write(encrypt(code, json.toString()).toByteArray()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (_: Exception) { false }

    /** Envoie la position (à appeler hors du fil principal). */
    fun publish(c: Context, loc: Location?, sos: Boolean, kind: String = "pos") {
        if (!Store.trackingEnabled(c)) return
        val code = Store.trackingCode(c)
        val json = JSONObject()
            .put("type", kind)
            .put("name", Store.childName(c))
            .put("t", System.currentTimeMillis())
            .put("bat", Locator.battery(c))
            .put("sos", sos)
        if (loc != null) json.put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toDouble())
        post(code, topic(code), json)
    }

    /** Messages reçus sur un canal : (identifiant ntfy, contenu déchiffré). */
    fun pollEvents(code: String, topic: String, since: String): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        val conn = (URL("$SERVER/$topic/json?poll=1&since=$since").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
        }
        val lines = conn.inputStream.bufferedReader().use { it.readLines() }
        lines.forEach { line ->
            try {
                val ev = JSONObject(line)
                if (ev.optString("event") != "message") return@forEach
                // Messages longs : ntfy les transforme en pièce jointe
                val body = ev.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() }?.let { url ->
                    (URL(url).openConnection() as HttpURLConnection).inputStream.bufferedReader().use { it.readText() }
                } ?: ev.optString("message")
                decrypt(code, body.trim())?.let { out += ev.optString("id") to JSONObject(it) }
            } catch (_: Exception) {}
        }
        return out
    }

    /** Côté parent : positions et états reçus. */
    fun poll(code: String, since: String): List<JSONObject> = pollEvents(code, topic(code), since).map { it.second }
}

/**
 * Service « gardien » du téléphone de l'enfant, toujours actif :
 * suivi de position (si activé), plages horaires, temps d'écran, alertes batterie, commandes à distance.
 */
class TrackingService : Service() {
    companion object {
        private const val MIN_INTERVAL = 6 * 60_000L
        private const val HEARTBEAT = 30 * 60_000L
        private const val MIN_DISTANCE = 100f
        private const val TICK = 60_000L

        fun sync(c: Context) {
            val i = Intent(c, TrackingService::class.java)
            try {
                if (Store.mode(c) == "child" && Locator.hasPermission(c)) c.startForegroundService(i) else c.stopService(i)
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSent: Location? = null
    private var lastSentAt = 0L
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = consider(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (Store.trackingEnabled(this@TrackingService)) {
                Locator.current(this@TrackingService, 30_000) { loc -> send(loc) }
            }
            handler.postDelayed(this, HEARTBEAT)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("suivi", "Protection du téléphone", NotificationManager.IMPORTANCE_MIN))
        val notif = Notification.Builder(this, "suivi")
            .setContentTitle("Tél Enfant veille sur ce téléphone")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(7, notif)
        updateListening()
        handler.post(heartbeat)
        handler.post(ticker)
        try { registerReceiver(shutdown, android.content.IntentFilter(Intent.ACTION_SHUTDOWN)) } catch (_: Exception) {}
    }

    private val shutdown = ShutdownReceiver()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Store.mode(this) != "child") stopSelf() else updateListening()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(shutdown) } catch (_: Exception) {}
        stopListening()
        super.onDestroy()
    }

    /** GPS uniquement quand le suivi est activé (économie de batterie). */
    @SuppressLint("MissingPermission")
    private fun updateListening() {
        val want = Store.trackingEnabled(this)
        if (want == listening) return
        if (!want) { stopListening(); return }
        val lm = getSystemService(LocationManager::class.java) ?: return
        lm.getProviders(true).forEach { p ->
            try { lm.requestLocationUpdates(p, 60_000L, 30f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        listening = true
    }

    private fun stopListening() {
        try { getSystemService(LocationManager::class.java)?.removeUpdates(listener) } catch (_: Exception) {}
        listening = false
    }

    private fun tick() {
        val app = applicationContext
        updateListening()
        try {
            Rules.flushUsage(app)
            Rules.enforce(app)
        } catch (_: Exception) {}
        checkBattery()
        if (Store.trackingEnabled(app)) Thread { try { Remote.pollCommands(app) } catch (_: Exception) {} }.start()
    }

    private fun checkBattery() {
        val app = applicationContext
        val level = Locator.battery(app)
        val charging = getSystemService(BatteryManager::class.java)?.isCharging == true
        if (charging || level >= 30) { Store.setBatteryAlerted(app, false); return }
        if (level in 0..15 && !Store.batteryAlerted(app) && Store.alertsEnabled(app)) {
            Store.setBatteryAlerted(app, true)
            Alerts.sms(app, "Batterie faible ($level %) sur le téléphone de ${Alerts.name(app)}. Pensez à le recharger.")
            Thread { Tracker.publish(app, Locator.lastKnown(app), sos = false) }.start()
        }
    }

    private fun consider(loc: Location) {
        val now = System.currentTimeMillis()
        val prev = lastSent
        val moved = prev == null || prev.distanceTo(loc) >= MIN_DISTANCE
        if ((moved && now - lastSentAt >= MIN_INTERVAL) || now - lastSentAt >= HEARTBEAT) send(loc)
    }

    private fun send(loc: Location?) {
        lastSentAt = System.currentTimeMillis()
        if (loc != null) lastSent = loc
        Thread { Tracker.publish(applicationContext, loc, sos = false) }.start()
    }
}

/** SMS automatiques aux parents (contacts SOS). */
object Alerts {
    fun name(c: Context) = Store.childName(c).ifBlank { "votre enfant" }

    fun sms(c: Context, text: String) {
        Thread { Sos.targets(c).forEach { SmsStore.send(c, it.number, text) } }.start()
    }
}

/** Extinction du téléphone : prévenir les parents. */
class ShutdownReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.mode(context) != "child" || !Store.alertsEnabled(context)) return
        val text = "Le téléphone de ${Alerts.name(context)} s'éteint (batterie ${Locator.battery(context)} %)."
        Sos.targets(context).forEach { SmsStore.send(context, it.number, text) }
    }
}
