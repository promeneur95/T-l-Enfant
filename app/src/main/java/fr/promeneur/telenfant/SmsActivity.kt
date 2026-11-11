package fr.promeneur.telenfant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.ImageView
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Conversation SMS avec un contact autorisé. */
class SmsActivity : Activity() {

    companion object {
        const val EXTRA_NUMBER = "number"
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
    }

    private lateinit var number: String
    private lateinit var thread: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val n = intent.getStringExtra(EXTRA_NUMBER)
            ?: intent.data?.schemeSpecificPart?.substringBefore('?')
        val contact = Store.find(this, n)
        if (n == null || contact == null) {
            Toast.makeText(this, "Numéro non autorisé", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        number = contact.number

        val root = Ui.vertical(this, 0).apply { setBackgroundColor(Ui.BG) }

        val top = Ui.horizontal(this).apply {
            setBackgroundColor(Ui.BLUE)
            setPadding(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
        }
        val back = Ui.button(this, "←", Ui.BLUE, 24f) { finish() }
        top.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 56), WRAP_CONTENT))
        top.addView(Ui.text(this, contact.name, 22f, Color.WHITE, true))
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        thread = Ui.vertical(this, 12)
        scroll = ScrollView(this).apply { addView(thread) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val bottom = Ui.horizontal(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
        }
        input = EditText(this).apply {
            hint = "Écrire un message…"
            textSize = 18f
            maxLines = 4
        }
        val send = Ui.button(this, "Envoyer", Ui.GREEN, 16f) {
            val txt = input.text.toString().trim()
            if (txt.isEmpty()) return@button
            if (SmsStore.send(this, number, txt)) {
                input.setText("")
                load()
            } else {
                Toast.makeText(this, "Envoi impossible", Toast.LENGTH_SHORT).show()
            }
        }
        val photo = Ui.button(this, "📷", Ui.BLUE, 20f) { choosePhoto() }
        bottom.addView(photo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = Ui.dp(this@SmsActivity, 6)
        })
        bottom.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottom.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!::number.isInitialized) return
        load()
        val f = IntentFilter(SmsStore.ACTION_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changed, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(changed, f)
    }

    override fun onPause() {
        try { unregisterReceiver(changed) } catch (_: Exception) {}
        super.onPause()
    }

    // ---------- Photos ----------
    private var cameraUri: Uri? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraUri?.let { outState.putString("camera_uri", it.toString()) }
    }

    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state)
        state.getString("camera_uri")?.let { cameraUri = Uri.parse(it) }
    }

    private fun choosePhoto() {
        AlertDialog.Builder(this)
            .setTitle("Envoyer une photo")
            .setItems(arrayOf("📷  Prendre une photo", "🖼️  Choisir une photo")) { _, which ->
                if (which == 0) takePhoto() else pickPhoto()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun takePhoto() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "TelEnfant_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TelEnfant")
            }
            cameraUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: Exception) {
            Toast.makeText(this, "Appareil photo indisponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickPhoto() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, "Galerie indisponible", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = when (requestCode) {
            REQ_CAMERA -> if (resultCode == RESULT_OK) cameraUri else {
                cameraUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                null
            }
            REQ_PICK -> if (resultCode == RESULT_OK) data?.data else null
            else -> null
        } ?: return
        confirmPhoto(uri)
    }

    private fun confirmPhoto(uri: Uri) {
        Toast.makeText(this, "Préparation de la photo…", Toast.LENGTH_SHORT).show()
        Thread {
            val jpeg = try { Mms.prepareJpeg(this, uri) } catch (e: Exception) { null }
            runOnUiThread {
                if (jpeg == null) {
                    Toast.makeText(this, "Photo illisible", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val preview = ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                    adjustViewBounds = true
                    maxHeight = Ui.dp(this@SmsActivity, 320)
                    setPadding(0, Ui.dp(this@SmsActivity, 12), 0, 0)
                }
                val caption = input.text.toString().trim()
                AlertDialog.Builder(this)
                    .setTitle("Envoyer cette photo ?")
                    .setMessage(if (caption.isEmpty()) "À ${contactName()}" else "À ${contactName()}, avec le texte :\n« $caption »")
                    .setView(preview)
                    .setPositiveButton("Envoyer") { _, _ ->
                        if (Mms.send(this, number, jpeg, caption.ifEmpty { null })) {
                            input.setText("")
                            Toast.makeText(this, "Photo en cours d'envoi…", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Envoi impossible", Toast.LENGTH_SHORT).show()
                        }
                        load()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }.start()
    }

    private fun contactName() = Store.find(this, number)?.name ?: number

    private fun thumbnail(path: String): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 600) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    // ---------- Fil de discussion ----------
    private fun load() {
        val msgs = SmsStore.conversation(this, number)
        val photos = PhotoLog.forNumber(this, number)
        // Fusion SMS + photos par date
        val items: List<Pair<Long, Any>> = (msgs.map { it.date to (it as Any) } + photos.map { it.date to (it as Any) })
            .sortedBy { it.first }
        thread.removeAllViews()
        if (items.isEmpty()) {
            thread.addView(Ui.text(this, "Aucun message.", 16f, Ui.GREY).apply { gravity = Gravity.CENTER })
        }
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE)
        items.forEach { (date, item) ->
            val incoming = when (item) {
                is Sms -> item.incoming
                is Photo -> item.incoming
                else -> false
            }
            val bubble = Ui.vertical(this, 10).apply {
                background = Ui.rounded(
                    if (incoming) Color.WHITE else 0xFFD7EED8.toInt(),
                    Ui.dp(context, 14).toFloat(), Ui.dp(context, 1), 0xFFDDE5DD.toInt()
                )
            }
            var footer = fmt.format(Date(date))
            when (item) {
                is Sms -> bubble.addView(Ui.text(this, item.body, 18f))
                is Photo -> {
                    val img = ImageView(this).apply {
                        setImageBitmap(thumbnail(item.path))
                        adjustViewBounds = true
                        maxWidth = Ui.dp(this@SmsActivity, 220)
                        maxHeight = Ui.dp(this@SmsActivity, 280)
                    }
                    bubble.addView(img, LinearLayout.LayoutParams(Ui.dp(this, 220), WRAP_CONTENT))
                    footer += when (item.status) {
                        "sending" -> " · envoi…"
                        "failed" -> " · ⚠️ non envoyée"
                        else -> ""
                    }
                }
            }
            bubble.addView(Ui.text(this, footer, 12f, Ui.GREY))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (incoming) Gravity.START else Gravity.END
                bottomMargin = Ui.dp(this@SmsActivity, 8)
                if (incoming) marginEnd = Ui.dp(this@SmsActivity, 48) else marginStart = Ui.dp(this@SmsActivity, 48)
            }
            thread.addView(bubble, lp)
        }
        SmsStore.markRead(this, msgs.filter { it.incoming }.map { it.id })
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
