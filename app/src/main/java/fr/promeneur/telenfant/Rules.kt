package fr.promeneur.telenfant

import android.content.Context
import android.os.PowerManager
import java.util.Calendar

/** Plages horaires (école, nuit) et temps d'écran. */
object Rules {
    enum class Mode { NORMAL, SCHOOL, NIGHT }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun toMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return (h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59))
    }

    fun validTime(s: String) = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(s.trim())

    private fun inRange(now: Int, start: Int, end: Int) =
        if (start <= end) now in start until end else now >= start || now < end

    fun mode(c: Context): Mode {
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (Store.nightEnabled(c) && inRange(now, toMinutes(Store.nightStart(c)), toMinutes(Store.nightEnd(c)))) return Mode.NIGHT
        if (Store.schoolEnabled(c) && cal.get(Calendar.DAY_OF_WEEK) in Store.schoolDays(c) &&
            inRange(now, toMinutes(Store.schoolStart(c)), toMinutes(Store.schoolEnd(c)))) return Mode.SCHOOL
        return Mode.NORMAL
    }

    /** En mode école, seuls les contacts SOS (les parents) restent joignables. */
    fun contactAllowedNow(c: Context, contact: Contact) = mode(c) != Mode.SCHOOL || contact.sos

    fun numberAllowedNow(c: Context, number: String?): Boolean {
        val contact = Store.find(c, number) ?: return false
        return contactAllowedNow(c, contact)
    }

    fun remainingSeconds(c: Context, pkg: String): Long? {
        val limit = Store.limits(c)[pkg] ?: return null
        val used = Store.usage(c)[pkg] ?: 0L
        return maxOf(0L, limit * 60L - used)
    }

    fun appAllowedNow(c: Context, pkg: String) =
        mode(c) == Mode.NORMAL && (remainingSeconds(c, pkg)?.let { it > 0 } ?: true)

    // ---------- Compteur d'utilisation ----------
    /** Ajoute le temps écoulé à l'appli en cours (écran allumé uniquement). */
    fun flushUsage(c: Context) {
        val now = System.currentTimeMillis()
        val pkg = Store.currentApp(c)
        val since = prefs(c).getLong("since", now)
        val interactive = c.getSystemService(PowerManager::class.java)?.isInteractive != false
        if (pkg.isNotEmpty() && interactive && now > since) {
            Store.addUsage(c, pkg, minOf((now - since) / 1000, 600))
        }
        prefs(c).edit().putLong("since", now).apply()
    }

    fun appOpened(c: Context, pkg: String) {
        flushUsage(c)
        Store.setCurrentApp(c, pkg)
    }

    fun backHome(c: Context) {
        flushUsage(c)
        Store.setCurrentApp(c, "")
    }

    // ---------- Application des règles ----------
    /** Met en pause (suspend) les applis interdites en ce moment, réactive les autres. */
    fun enforce(c: Context) {
        if (!Policy.isOwner(c)) return
        val d = Policy.dpm(c)
        val a = Policy.admin(c)
        val apps = Store.allowedApps(c).toMutableSet()
        if (Store.cameraEnabled(c)) CameraApp.packageName(c)?.let { apps += it }
        val previously = prefs(c).getStringSet("suspended", emptySet())?.toSet() ?: emptySet()
        val suspend = apps.filter { !appAllowedNow(c, it) }.toSet()
        val release = (previously + apps) - suspend
        try {
            if (suspend.isNotEmpty()) d.setPackagesSuspended(a, suspend.toTypedArray(), true)
            if (release.isNotEmpty()) d.setPackagesSuspended(a, release.toTypedArray(), false)
        } catch (_: Exception) {}
        prefs(c).edit().putStringSet("suspended", suspend).apply()
    }

    fun banner(c: Context): String? = when (mode(c)) {
        Mode.SCHOOL -> "🏫 Mode école jusqu'à ${Store.schoolEnd(c)} : seuls tes parents et le SOS"
        Mode.NIGHT -> "🌙 Mode nuit jusqu'à ${Store.nightEnd(c)} : les applis dorment"
        Mode.NORMAL -> null
    }
}
