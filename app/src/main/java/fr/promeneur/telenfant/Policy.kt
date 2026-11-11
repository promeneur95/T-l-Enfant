package fr.promeneur.telenfant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log

/** Application et levée du bridage (nécessite le statut « propriétaire de l'appareil »). */
object Policy {
    private const val TAG = "TelEnfant"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_USB_FILE_TRANSFER,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_CONFIG_CREDENTIALS,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
    )

    private val PERMISSIONS = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
    )

    fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)!!
    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Log.w(TAG, "$what : ${e.message}") }
    }

    fun apply(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        val pkg = c.packageName

        RESTRICTIONS.forEach { r -> safe(r) { d.addUserRestriction(a, r) } }
        safe("uninstall") { d.setUninstallBlocked(a, pkg, true) }

        PERMISSIONS.forEach { perm ->
            safe(perm) { d.setPermissionGrantState(a, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        }

        // Kiosque : seule notre appli peut tourner
        safe("locktask") { d.setLockTaskPackages(a, arrayOf(pkg)) }
        safe("locktask features") {
            d.setLockTaskFeatures(
                a,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or   // menu Éteindre
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or  // heure, batterie, réseau
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            )
        }

        // Notre écran devient l'écran d'accueil imposé
        safe("home") {
            val f = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(a, f, ComponentName(c, HomeActivity::class.java))
        }

        // Appli téléphone et SMS par défaut
        if (Build.VERSION.SDK_INT >= 34) safe("dialer") { d.setDefaultDialerApplication(pkg) }
        safe("sms") { d.setDefaultSmsApplication(a, pkg) }
    }

    /** Lève tout et rend le téléphone normal (sans réinitialisation). */
    fun release(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        val pkg = c.packageName
        RESTRICTIONS.forEach { r -> safe(r) { d.clearUserRestriction(a, r) } }
        safe("uninstall") { d.setUninstallBlocked(a, pkg, false) }
        safe("home") { d.clearPackagePersistentPreferredActivities(a, pkg) }
        safe("locktask") { d.setLockTaskPackages(a, arrayOf()) }
        @Suppress("DEPRECATION")
        safe("owner") { d.clearDeviceOwnerApp(pkg) }
    }
}
