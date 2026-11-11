package fr.promeneur.telenfant

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.telephony.SmsManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class Photo(val id: Long, val key: String, val path: String, val date: Long, val incoming: Boolean, val status: String)

/** Historique local des photos envoyées (fichiers dans l'espace privé de l'appli). */
object PhotoLog {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("photos", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "photos").apply { mkdirs() }

    fun all(c: Context): List<Photo> = try {
        val arr = JSONArray(p(c).getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Photo(o.getLong("id"), o.getString("key"), o.getString("path"), o.getLong("date"),
                o.optBoolean("incoming"), o.optString("status", "sent"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, list: List<Photo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("key", it.key).put("path", it.path)
                .put("date", it.date).put("incoming", it.incoming).put("status", it.status))
        }
        p(c).edit().putString("list", arr.toString()).apply()
    }

    @Synchronized
    fun add(c: Context, number: String, jpeg: ByteArray, incoming: Boolean, status: String): Photo {
        val id = System.currentTimeMillis()
        val f = File(dir(c), "$id.jpg").apply { writeBytes(jpeg) }
        val photo = Photo(id, Store.key(number), f.absolutePath, id, incoming, status)
        save(c, all(c) + photo)
        return photo
    }

    @Synchronized
    fun setStatus(c: Context, id: Long, status: String) {
        save(c, all(c).map { if (it.id == id) it.copy(status = status) else it })
    }

    fun forNumber(c: Context, number: String): List<Photo> {
        val k = Store.key(number)
        return all(c).filter { it.key == k }
    }
}

/** Envoi de photos par MMS (encodage du message au format MMS « m-send-req »). */
object Mms {
    private const val MAX_BYTES = 280_000
    private const val AUTHORITY = "fr.promeneur.telenfant.mms"

    fun pduDir(c: Context) = File(c.cacheDir, "mms").apply { mkdirs() }

    /** Lit l'image, la redresse, la réduit et la compresse sous la taille maximale d'un MMS. */
    fun prepareJpeg(c: Context, uri: Uri): ByteArray? {
        val cr = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = cr.openInputStream(uri) ?: return null
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        if (rotation != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        }

        var maxSide = 1280
        while (true) {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            else bmp
            for (q in intArrayOf(85, 75, 65, 55, 45)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= MAX_BYTES) return out.toByteArray()
            }
            if (maxSide <= 480) return null
            maxSide = (maxSide * 0.75).toInt()
        }
    }

    /** Numéro au format international pour l'en-tête MMS. */
    private fun international(number: String): String {
        val plus = number.trim().startsWith("+")
        val d = number.filter { it.isDigit() }
        return when {
            plus -> "+$d"
            d.startsWith("00") -> "+" + d.drop(2)
            d.length == 10 && d.startsWith("0") -> "+33" + d.drop(1)
            else -> d
        }
    }

    // ---------- Encodage binaire MMS (OMA MMS Encapsulation 1.2) ----------
    private fun ByteArrayOutputStream.text(s: String) {
        write(s.toByteArray(Charsets.UTF_8)); write(0)
    }

    private fun ByteArrayOutputStream.uintvar(value: Int) {
        var v = value
        val bytes = ArrayList<Int>()
        bytes.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) { bytes.add((v and 0x7F) or 0x80); v = v ushr 7 }
        bytes.reversed().forEach { write(it) }
    }

    private fun part(out: ByteArrayOutputStream, contentType: ByteArray, location: String, data: ByteArray) {
        val headers = ByteArrayOutputStream().apply {
            write(contentType)
            write(0x8E); text(location) // Content-Location
        }.toByteArray()
        out.uintvar(headers.size)
        out.uintvar(data.size)
        out.write(headers)
        out.write(data)
    }

    fun encode(to: String, jpeg: ByteArray, caption: String?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x80)                                   // Message-Type : m-send-req
        out.write(0x98); out.text("T" + System.currentTimeMillis())          // Transaction-Id
        out.write(0x8D); out.write(0x92)                                   // MMS-Version 1.2
        out.write(0x89); out.write(0x01); out.write(0x81)                  // From : insérée par l'opérateur
        out.write(0x97); out.text(international(to) + "/TYPE=PLMN")       // To
        out.write(0x84); out.write(0xA3)                                   // Content-Type : multipart/mixed
        val parts = if (caption.isNullOrBlank()) 1 else 2
        out.uintvar(parts)
        part(out, byteArrayOf(0x9E.toByte()), "photo.jpg", jpeg)           // image/jpeg
        if (!caption.isNullOrBlank()) {
            // text/plain ; charset=utf-8
            part(out, byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte()), "texte.txt",
                caption.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    /** Envoie la photo (uniquement vers un contact autorisé). */
    @Suppress("DEPRECATION")
    fun send(c: Context, number: String, jpeg: ByteArray, caption: String?): Boolean {
        if (!Store.isAllowed(c, number)) return false
        val photo = PhotoLog.add(c, number, jpeg, incoming = false, status = "sending")
        return try {
            val file = File(pduDir(c), "${photo.id}.pdu").apply { writeBytes(encode(number, jpeg, caption)) }
            val uri = Uri.parse("content://$AUTHORITY/${file.name}")
            val pi = PendingIntent.getBroadcast(
                c, (photo.id % Int.MAX_VALUE).toInt(),
                Intent(c, MmsSentReceiver::class.java).putExtra("id", photo.id).putExtra("file", file.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            sm.sendMultimediaMessage(c, uri, null, null, pi)
            if (!caption.isNullOrBlank()) SmsStore.insert(c, number, caption, incoming = false)
            SmsStore.changed(c)
            true
        } catch (e: Exception) {
            PhotoLog.setStatus(c, photo.id, "failed")
            false
        }
    }
}

/** Résultat d'envoi d'un MMS. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        intent.getStringExtra("file")?.let { File(Mms.pduDir(context), it).delete() }
        if (resultCode == Activity.RESULT_OK) {
            PhotoLog.setStatus(context, id, "sent")
        } else {
            PhotoLog.setStatus(context, id, "failed")
            Toast.makeText(context, "Photo non envoyée. Vérifiez que les données mobiles sont activées.", Toast.LENGTH_LONG).show()
        }
        SmsStore.changed(context)
    }
}

/** Met le message MMS à disposition du service d'envoi du téléphone (processus système uniquement). */
class MmsPduProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val uid = Binder.getCallingUid()
        if (uid >= Process.FIRST_APPLICATION_UID && uid != Process.myUid()) throw SecurityException("refusé")
        val ctx = context ?: return null
        val dir = Mms.pduDir(ctx).canonicalFile
        val f = File(dir, uri.lastPathSegment ?: return null).canonicalFile
        if (f.parentFile != dir || !f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.wap.mms-message"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
