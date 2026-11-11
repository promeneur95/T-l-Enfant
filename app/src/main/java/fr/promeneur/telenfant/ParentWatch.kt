package fr.promeneur.telenfant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Zone(val name: String, val lat: Double, val lon: Double, val radius: Int)

/** Côté parent : zones (maison, école…) et mémoire des alertes déjà signalées. */
object ParentStore {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("parent", Context.MODE_PRIVATE)

    fun zones(c: Context): List<Zone> = try {
        val a = JSONArray(p(c).getString("zones", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Zone(o.getString("n"), o.getDouble("lat"), o.getDouble("lon"), o.optInt("r", 150))
        }
    } catch (e: Exception) { emptyList() }

    fun saveZones(c: Context, list: List<Zone>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("lat", it.lat).put("lon", it.lon).put("r", it.radius)) }
        p(c).edit().putString("zones", a.toString()).apply()
    }

    fun zonesJson(c: Context): JSONArray {
        val a = JSONArray()
        zones(c).forEach { a.put(JSONObject().put("n", it.name).put("lat", it.lat).put("lon", it.lon).put("r", it.radius)) }
        return a
    }

    fun get(c: Context, k: String) = p(c).getString(k, "") ?: ""
    fun put(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()
    fun getLong(c: Context, k: String) = p(c).getLong(k, 0L)
    fun putLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).apply()

    /** Dernier état reçu du téléphone de l'enfant (contacts, applis, journal). */
    fun lastStatus(c: Context): JSONObject? = try {
        get(c, "status").takeIf { it.isNotEmpty() }?.let { JSONObject(it) }
    } catch (e: Exception) { null }
}

/** Vérification périodique en arrière-plan (toutes les 15 min) sur le téléphone parent. */
class ParentJob : JobService() {
    companion object {
        private const val JOB_ID = 4242

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, ParentJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        fun notify(c: Context, id: Int, title: String, text: String, urgent: Boolean) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel("alertes", "Alertes Tél Enfant", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("infos", "Infos Tél Enfant", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 0, Intent(c, ParentActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, if (urgent) "alertes" else "infos")
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(if (urgent) android.R.drawable.stat_sys_warning else android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { nm.notify(id, n) } catch (_: Exception) {}
        }

        /** Analyse les derniers messages et prévient le parent si besoin. */
        fun check(c: Context, points: List<JSONObject>) {
            if (points.isEmpty()) return
            points.filter { it.optString("type") == "status" }.maxByOrNull { it.optLong("t") }?.let {
                if (it.optLong("t") > (ParentStore.lastStatus(c)?.optLong("t") ?: 0)) ParentStore.put(c, "status", it.toString())
            }
            val latest = points.maxByOrNull { it.optLong("t") } ?: return
            val name = latest.optString("name").ifBlank { "Votre enfant" }
            val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)

            // SOS
            points.filter { it.optBoolean("sos") }.maxByOrNull { it.optLong("t") }?.let { sos ->
                if (sos.optLong("t") > ParentStore.getLong(c, "sos_t")) {
                    ParentStore.putLong(c, "sos_t", sos.optLong("t"))
                    notify(c, 1, "🆘 SOS de $name", "Alerte déclenchée à ${fmt.format(Date(sos.optLong("t")))}. Ouvrez la carte pour voir sa position.", true)
                }
            }

            // Zones
            points.filter { it.has("lat") }.maxByOrNull { it.optLong("t") }?.let { pos ->
                val here = ParentStore.zones(c).firstOrNull { z ->
                    val d = FloatArray(1)
                    Location.distanceBetween(pos.optDouble("lat"), pos.optDouble("lon"), z.lat, z.lon, d)
                    d[0] <= z.radius + minOf(pos.optDouble("acc", 0.0), 100.0)
                }?.name ?: ""
                val before = ParentStore.get(c, "zone")
                if (here != before && pos.optLong("t") > ParentStore.getLong(c, "zone_t")) {
                    ParentStore.put(c, "zone", here)
                    ParentStore.putLong(c, "zone_t", pos.optLong("t"))
                    val time = fmt.format(Date(pos.optLong("t")))
                    if (here.isNotEmpty()) notify(c, 2, "📍 $name est arrivé·e : $here", "À $time.", false)
                    else if (before.isNotEmpty()) notify(c, 2, "🚶 $name a quitté : $before", "À $time.", false)
                }
            }

            // Batterie faible
            val bat = latest.optInt("bat", 100)
            if (bat in 0..15 && ParentStore.get(c, "bat_alert") != "1") {
                ParentStore.put(c, "bat_alert", "1")
                notify(c, 3, "🪫 Batterie faible ($bat %)", "Le téléphone de $name va bientôt s'éteindre.", false)
            } else if (bat >= 30) ParentStore.put(c, "bat_alert", "")

            // Sans nouvelles depuis 2 h
            val age = System.currentTimeMillis() - latest.optLong("t")
            if (age > 2 * 3600_000L && ParentStore.getLong(c, "silence_t") != latest.optLong("t")) {
                ParentStore.putLong(c, "silence_t", latest.optLong("t"))
                notify(c, 4, "❓ Pas de nouvelles de $name",
                    "Aucune position depuis ${fmt.format(Date(latest.optLong("t")))}. Téléphone éteint, sans réseau ou données coupées ?", true)
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val code = Store.parentCode(this)
        if (code.isBlank()) return false
        Thread {
            try { check(applicationContext, Tracker.poll(code, "12h")) } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?) = true
}
