package fr.promeneur.telenfant

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Telephony
import android.telephony.SmsManager

data class Sms(val id: Long, val body: String, val date: Long, val incoming: Boolean)

/** Lecture / écriture dans la base SMS du téléphone (rôle « SMS par défaut »). */
object SmsStore {
    const val ACTION_CHANGED = "fr.promeneur.telenfant.SMS_CHANGED"

    fun changed(c: Context) = c.sendBroadcast(Intent(ACTION_CHANGED).setPackage(c.packageName))

    fun insert(c: Context, address: String, body: String, incoming: Boolean, sentAt: Long = System.currentTimeMillis()) {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAt)
            put(Telephony.Sms.READ, if (incoming) 0 else 1)
            put(Telephony.Sms.SEEN, if (incoming) 0 else 1)
            put(Telephony.Sms.TYPE, if (incoming) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        val uri = if (incoming) Telephony.Sms.Inbox.CONTENT_URI else Telephony.Sms.Sent.CONTENT_URI
        try { c.contentResolver.insert(uri, v) } catch (_: Exception) {}
    }

    /** Conversation avec un numéro (comparaison sur les 9 derniers chiffres). */
    fun conversation(c: Context, number: String): List<Sms> {
        val key = Store.key(number)
        val out = mutableListOf<Sms>()
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC"
            )?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n < 2000) {
                    n++
                    if (Store.key(cur.getString(1)) != key) continue
                    out += Sms(cur.getLong(0), cur.getString(2) ?: "", cur.getLong(3),
                        cur.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX)
                    if (out.size >= 300) break
                }
            }
        } catch (_: Exception) {}
        return out.reversed()
    }

    fun unreadByKey(c: Context): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        try {
            c.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS),
                Telephony.Sms.READ + "=0", null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val k = Store.key(cur.getString(0))
                    map[k] = (map[k] ?: 0) + 1
                }
            }
        } catch (_: Exception) {}
        return map
    }

    fun markRead(c: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        val v = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        try {
            c.contentResolver.update(
                Telephony.Sms.CONTENT_URI, v,
                Telephony.Sms._ID + " IN (" + ids.joinToString(",") + ")", null
            )
        } catch (_: Exception) {}
    }

    /** Envoi d'un SMS (uniquement vers un numéro autorisé). */
    @Suppress("DEPRECATION")
    fun send(c: Context, number: String, body: String): Boolean {
        if (!Store.isAllowed(c, number) || body.isBlank()) return false
        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            val parts = sm.divideMessage(body)
            if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, null, null)
            else sm.sendTextMessage(number, null, body, null, null)
            insert(c, number, body, incoming = false)
            changed(c)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun alert(c: Context) {
        try { RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() } catch (_: Exception) {}
        try {
            val v = c.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}

/** Réception des SMS : seuls ceux des contacts autorisés sont conservés. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val sender = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        if (!Store.isAllowed(context, sender)) {
            Store.incBlocked(context)
            return
        }
        SmsStore.insert(context, sender, body, incoming = true, sentAt = msgs[0].timestampMillis)
        SmsStore.alert(context)
        SmsStore.changed(context)
    }
}

/** MMS ignorés (requis pour être appli SMS par défaut). */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** « Répondre par message » depuis un appel entrant : limité aux contacts autorisés. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (number != null && text != null) SmsStore.send(this, number, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
