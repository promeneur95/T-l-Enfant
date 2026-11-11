package fr.promeneur.telenfant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Pilotage à distance : le téléphone parent envoie des commandes chiffrées
 * sur un second canal ; le téléphone de l'enfant les lit chaque minute.
 */
object Remote {
    fun cmdTopic(code: String) = Tracker.topic(code) + "c"

    // ---------- Côté parent ----------
    fun send(code: String, cmd: JSONObject): Boolean {
        val id = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789"[SecureRandom().nextInt(36)] }.joinToString("")
        cmd.put("id", id).put("t", System.currentTimeMillis())
        return Tracker.post(code, cmdTopic(code), cmd)
    }

    // ---------- Côté enfant ----------
    fun pollCommands(c: Context) {
        val code = Store.trackingCode(c)
        val since = Store.lastCommandId(c).ifEmpty { "1h" }
        val events = try { Tracker.pollEvents(code, cmdTopic(code), since) } catch (e: Exception) { return }
        var changed = false
        var statusAsked = false
        events.forEach { (ntfyId, cmd) ->
            if (ntfyId.isNotEmpty()) Store.setLastCommandId(c, ntfyId)
            if (System.currentTimeMillis() - cmd.optLong("t") > 2 * 3600_000L) return@forEach
            if (Store.commandSeen(c, cmd.optString("id"))) return@forEach
            when (cmd.optString("cmd")) {
                "ring" -> Handler(Looper.getMainLooper()).post { Ringer.ring(c, 30) }
                "locate" -> Locator.current(c, 30_000) { loc -> Thread { Tracker.publish(c, loc, sos = false) }.start() }
                "status" -> statusAsked = true
                "contact_add" -> {
                    val number = cmd.optString("number")
                    if (Store.key(number).length >= 9 && Store.find(c, number) == null) {
                        Store.saveContacts(c, Store.contacts(c) + Contact(
                            cmd.optString("name").ifBlank { number }, number,
                            cmd.optString("emoji").ifBlank { "🙂" }, cmd.optBoolean("sos")
                        ))
                        changed = true
                    }
                }
                "contact_del" -> {
                    val k = Store.key(cmd.optString("number"))
                    Store.saveContacts(c, Store.contacts(c).filter { Store.key(it.number) != k })
                    changed = true
                }
                "contact_sos" -> {
                    val k = Store.key(cmd.optString("number"))
                    Store.saveContacts(c, Store.contacts(c).map { if (Store.key(it.number) == k) it.copy(sos = cmd.optBoolean("sos")) else it })
                    changed = true
                }
                "app" -> {
                    val pkg = cmd.optString("pkg")
                    val set = Store.allowedApps(c).toMutableSet()
                    if (cmd.optBoolean("allow")) set += pkg else set -= pkg
                    Store.setAllowedApps(c, set)
                    Policy.apply(c)
                    changed = true
                }
                "limit" -> {
                    Store.setLimit(c, cmd.optString("pkg"), cmd.optInt("minutes"))
                    changed = true
                }
                "camera" -> { Store.setCameraEnabled(c, cmd.optBoolean("on")); changed = true }
                else -> {}
            }
        }
        if (changed) {
            Rules.enforce(c)
            SmsStore.changed(c) // rafraîchit l'accueil
        }
        if (changed || statusAsked) publishStatus(c)
    }

    /** Envoie l'état complet du téléphone (contacts, applis, journal…). */
    fun publishStatus(c: Context) {
        if (!Store.trackingEnabled(c)) return
        val pm = c.packageManager
        val allowed = Store.allowedApps(c)
        val limits = Store.limits(c)
        val usage = Store.usage(c)
        val hidden = setOf(c.packageName, AdminActivity.PLAY_STORE, "com.android.settings") + Policy.mediaPackages(c)
        val apps = JSONArray()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct().filter { it !in hidden }
            .forEach { pkg ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                apps.put(JSONObject().put("p", pkg).put("l", label).put("a", pkg in allowed)
                    .put("m", limits[pkg] ?: 0).put("u", (usage[pkg] ?: 0L) / 60))
            }
        val contacts = JSONArray()
        Store.contacts(c).forEach {
            contacts.put(JSONObject().put("n", it.name).put("num", it.number).put("e", it.emoji).put("s", it.sos))
        }
        val journal = JSONArray()
        val j = Store.journal(c)
        for (i in maxOf(0, j.length() - 20) until j.length()) journal.put(j.get(i))
        val json = JSONObject()
            .put("type", "status")
            .put("name", Store.childName(c))
            .put("t", System.currentTimeMillis())
            .put("bat", Locator.battery(c))
            .put("ver", Updater.currentVersion(c))
            .put("mode", Rules.mode(c).name)
            .put("cam", Store.cameraEnabled(c))
            .put("contacts", contacts)
            .put("apps", apps)
            .put("journal", journal)
        val code = Store.trackingCode(c)
        Tracker.post(code, Tracker.topic(code), json)
    }
}

/** Sonnerie forte pour retrouver le téléphone (même en mode vibreur). */
object Ringer {
    private var player: MediaPlayer? = null

    fun ring(c: Context, seconds: Int) {
        stop()
        try {
            val am = c.getSystemService(AudioManager::class.java)
            am?.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(c, uri)
                isLooping = true
                prepare()
                start()
            }
            Hold.vibrate(c, 1500)
            Handler(Looper.getMainLooper()).postDelayed({ stop() }, seconds * 1000L)
        } catch (_: Exception) {}
    }

    fun stop() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
    }
}
