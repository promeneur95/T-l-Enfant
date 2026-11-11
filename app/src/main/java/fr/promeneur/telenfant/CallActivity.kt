package fr.promeneur.telenfant

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Écran d'appel (entrant et sortant). */
class CallActivity : Activity() {

    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var answer: Button
    private lateinit var speaker: Button
    private lateinit var mute: Button
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = Ui.vertical(this, 24).apply {
            setBackgroundColor(0xFF12301A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(Ui.space(this, 60))
        name = Ui.text(this, "", 34f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        status = Ui.text(this, "", 20f, 0xFFB9D9BF.toInt()).apply { gravity = Gravity.CENTER }
        root.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Ui.space(this, 8))
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val toggles = Ui.horizontal(this)
        speaker = Ui.button(this, "🔊 Haut-parleur", Ui.GREY, 16f) {
            CallManager.setSpeaker(!CallManager.isSpeaker()); refresh()
        }
        mute = Ui.button(this, "🎤 Micro", Ui.GREY, 16f) {
            CallManager.setMuted(!CallManager.isMuted()); refresh()
        }
        toggles.addView(speaker, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@CallActivity, 6) })
        toggles.addView(mute, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(this@CallActivity, 6) })
        root.addView(toggles, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Ui.space(this, 24))

        val actions = Ui.horizontal(this)
        answer = Ui.button(this, "📞 Décrocher", Ui.GREEN, 20f) {
            CallManager.current?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
        val hang = Ui.button(this, "Raccrocher", Ui.RED, 20f) {
            val c = CallManager.current ?: run { finish(); return@button }
            if (stateOf(c) == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
        }
        actions.addView(answer, LinearLayout.LayoutParams(0, Ui.dp(this, 72), 1f).apply { marginEnd = Ui.dp(this@CallActivity, 6) })
        actions.addView(hang, LinearLayout.LayoutParams(0, Ui.dp(this, 72), 1f).apply { marginStart = Ui.dp(this@CallActivity, 6) })
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Ui.space(this, 24))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        CallManager.addListener(listener)
        handler.post(ticker)
    }

    override fun onPause() {
        CallManager.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* rester sur l'écran d'appel */ }

    private fun refresh() {
        val call = CallManager.current
        if (call == null) {
            finish()
            return
        }
        val number = call.details.handle?.schemeSpecificPart ?: ""
        name.text = Store.find(this, number)?.name
            ?: if (Store.isEmergencyNumber(number)) "Urgences $number" else number
        val state = stateOf(call)
        status.text = when (state) {
            Call.STATE_RINGING -> "Appel entrant…"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Appel en cours…"
            Call.STATE_ACTIVE -> duration(call)
            Call.STATE_HOLDING -> "En attente"
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Appel terminé"
            else -> ""
        }
        answer.visibility = if (state == Call.STATE_RINGING) android.view.View.VISIBLE else android.view.View.GONE
        speaker.alpha = if (CallManager.isSpeaker()) 1f else 0.6f
        speaker.text = if (CallManager.isSpeaker()) "🔊 Haut-parleur ON" else "🔊 Haut-parleur"
        mute.alpha = if (CallManager.isMuted()) 1f else 0.6f
        mute.text = if (CallManager.isMuted()) "🔇 Micro coupé" else "🎤 Micro"
    }

    private fun duration(call: Call): String {
        val start = call.details.connectTimeMillis
        if (start <= 0) return "En communication"
        val s = (System.currentTimeMillis() - start) / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}

@Suppress("DEPRECATION")
fun stateOf(call: Call): Int = call.state
