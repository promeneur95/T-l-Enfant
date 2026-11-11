package fr.promeneur.telenfant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class Contact(val name: String, val number: String, val emoji: String = "🙂", val sos: Boolean = false)

/** Stockage local : liste blanche, code admin, réglages. */
object Store {
    private const val FILE = "tel_enfant"
    /** Après un appel d'urgence, tous les appels entrants sont acceptés pendant ce délai (rappel des secours). */
    private const val EMERGENCY_WINDOW_MS = 60 * 60 * 1000L

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- Contacts autorisés ----------
    fun contacts(c: Context): List<Contact> {
        val raw = p(c).getString("contacts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Contact(o.optString("name"), o.optString("number"), o.optString("emoji", "🙂"), o.optBoolean("sos", false))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveContacts(c: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("number", it.number).put("emoji", it.emoji).put("sos", it.sos))
        }
        p(c).edit().putString("contacts", arr.toString()).apply()
    }

    /** Compare sur les 9 derniers chiffres : 06 12 34 56 78 = +33 6 12 34 56 78. */
    fun key(number: String?): String = (number ?: "").filter { it.isDigit() }.takeLast(9)

    fun find(c: Context, number: String?): Contact? {
        val k = key(number)
        if (k.length < 3) return null
        return contacts(c).firstOrNull { key(it.number) == k }
    }

    fun isAllowed(c: Context, number: String?): Boolean = find(c, number) != null

    // ---------- Urgences ----------
    fun markEmergencyCall(c: Context) {
        p(c).edit().putLong("emergency_at", System.currentTimeMillis()).apply()
    }

    fun inEmergencyWindow(c: Context): Boolean =
        System.currentTimeMillis() - p(c).getLong("emergency_at", 0) < EMERGENCY_WINDOW_MS

    fun isEmergencyNumber(number: String): Boolean =
        number.filter { it.isDigit() } in setOf("112", "15", "17", "18", "114", "115", "119", "191", "196", "197")

    /** Appel entrant accepté ? */
    fun incomingAllowed(c: Context, number: String?): Boolean =
        isAllowed(c, number) || inEmergencyWindow(c)

    // ---------- Code admin ----------
    fun hasPin(c: Context) = p(c).contains("pin_hash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        p(c).edit().putString("pin_salt", saltHex).putString("pin_hash", hash(saltHex, pin)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = p(c).getString("pin_salt", null) ?: return false
        return hash(salt, pin) == p(c).getString("pin_hash", null)
    }

    private fun hash(salt: String, pin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---------- Divers ----------
    var lockSuspendedUntil = 0L // en mémoire : pause du kiosque demandée par le parent

    fun blockedCount(c: Context) = p(c).getInt("blocked", 0)
    fun incBlocked(c: Context) = p(c).edit().putInt("blocked", blockedCount(c) + 1).apply()
    fun resetBlocked(c: Context) = p(c).edit().putInt("blocked", 0).apply()

    fun lastUpdateCheck(c: Context) = p(c).getLong("last_update_check", 0)
    fun setLastUpdateCheck(c: Context) =
        p(c).edit().putLong("last_update_check", System.currentTimeMillis()).apply()

    // ---------- Personnalisation ----------
    fun childName(c: Context) = p(c).getString("child_name", "") ?: ""
    fun setChildName(c: Context, v: String) = p(c).edit().putString("child_name", v).apply()

    fun themeIndex(c: Context) = p(c).getInt("theme", 0)
    fun customColor(c: Context) = p(c).getInt("custom_color", 0)
    fun setTheme(c: Context, index: Int) = p(c).edit().putInt("theme", index).putInt("custom_color", 0).apply()
    fun setCustomColor(c: Context, color: Int) = p(c).edit().putInt("custom_color", color).apply()

    /** "child", "parent" ou "" (pas encore choisi). */
    fun mode(c: Context) = p(c).getString("mode", "") ?: ""
    fun setMode(c: Context, v: String) = p(c).edit().putString("mode", v).apply()

    // ---------- Suivi de position ----------
    fun trackingEnabled(c: Context) = p(c).getBoolean("tracking", false)
    fun setTrackingEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("tracking", v).apply()
    fun trackingCode(c: Context): String {
        val existing = p(c).getString("tracking_code", null)
        if (existing != null) return existing
        val code = Tracker.newCode()
        p(c).edit().putString("tracking_code", code).apply()
        return code
    }
    fun renewTrackingCode(c: Context) = p(c).edit().putString("tracking_code", Tracker.newCode()).apply()

    /** Côté téléphone parent : code du téléphone suivi. */
    fun parentCode(c: Context) = p(c).getString("parent_code", "") ?: ""
    fun setParentCode(c: Context, v: String) = p(c).edit().putString("parent_code", v).apply()

    // ---------- Applications autorisées ----------
    fun allowedApps(c: Context): Set<String> = p(c).getStringSet("apps", emptySet())?.toSet() ?: emptySet()
    fun setAllowedApps(c: Context, v: Set<String>) = p(c).edit().putStringSet("apps", v).apply()

    // ---------- Son et appareil photo ----------
    /** Mode au démarrage du téléphone : vibreur (true) ou sonnerie (false). */
    fun defaultVibrate(c: Context) = p(c).getBoolean("default_vibrate", false)
    fun setDefaultVibrate(c: Context, v: Boolean) = p(c).edit().putBoolean("default_vibrate", v).apply()

    fun cameraEnabled(c: Context) = p(c).getBoolean("camera_app", false)
    fun setCameraEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("camera_app", v).apply()

    // ---------- Messagerie vocale ----------
    fun voicemailOverride(c: Context) = p(c).getString("voicemail", "") ?: ""
    fun setVoicemailOverride(c: Context, v: String) = p(c).edit().putString("voicemail", v).apply()
}
