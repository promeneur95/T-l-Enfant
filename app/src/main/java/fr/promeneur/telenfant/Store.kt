package fr.promeneur.telenfant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class Contact(val name: String, val number: String)

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
                Contact(o.optString("name"), o.optString("number"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveContacts(c: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
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
}
