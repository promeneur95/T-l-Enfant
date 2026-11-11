package fr.promeneur.telenfant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    companion object { const val EXTRA_NUMBER = "number" }

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

    private fun load() {
        val msgs = SmsStore.conversation(this, number)
        thread.removeAllViews()
        if (msgs.isEmpty()) {
            thread.addView(Ui.text(this, "Aucun message.", 16f, Ui.GREY).apply { gravity = Gravity.CENTER })
        }
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE)
        msgs.forEach { m ->
            val bubble = Ui.vertical(this, 10).apply {
                background = Ui.rounded(
                    if (m.incoming) Color.WHITE else 0xFFD7EED8.toInt(),
                    Ui.dp(context, 14).toFloat(), Ui.dp(context, 1), 0xFFDDE5DD.toInt()
                )
            }
            bubble.addView(Ui.text(this, m.body, 18f))
            bubble.addView(Ui.text(this, fmt.format(Date(m.date)), 12f, Ui.GREY))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (m.incoming) Gravity.START else Gravity.END
                bottomMargin = Ui.dp(this@SmsActivity, 8)
                if (m.incoming) marginEnd = Ui.dp(this@SmsActivity, 48) else marginStart = Ui.dp(this@SmsActivity, 48)
            }
            thread.addView(bubble, lp)
        }
        SmsStore.markRead(this, msgs.filter { it.incoming }.map { it.id })
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
