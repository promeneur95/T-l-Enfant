package fr.promeneur.telenfant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View

/** Une ambiance de couleurs pour l'écran d'accueil. */
data class Palette(
    val name: String,
    val emoji: String,
    val top: Int,        // haut du dégradé de fond
    val bottom: Int,     // bas du dégradé de fond
    val accent: Int,     // boutons principaux
    val cards: List<Int> // couleurs des cartes contacts
)

object Themes {
    val list = listOf(
        Palette("Jungle", "🦁", 0xFF43A047.toInt(), 0xFFDCEDC8.toInt(), 0xFF2E7D32.toInt(),
            listOf(0xFFFFF59D, 0xFFFFCC80, 0xFFA5D6A7, 0xFF80DEEA).map { it.toInt() }),
        Palette("Océan", "🐬", 0xFF1E88E5.toInt(), 0xFFB3E5FC.toInt(), 0xFF1565C0.toInt(),
            listOf(0xFFB2EBF2, 0xFFFFF9C4, 0xFFC5CAE9, 0xFFB2DFDB).map { it.toInt() }),
        Palette("Bonbon", "🍭", 0xFFEC407A.toInt(), 0xFFFCE4EC.toInt(), 0xFFD81B60.toInt(),
            listOf(0xFFF8BBD0, 0xFFE1BEE7, 0xFFFFF59D, 0xFFB3E5FC).map { it.toInt() }),
        Palette("Espace", "🚀", 0xFF283593.toInt(), 0xFF7E57C2.toInt(), 0xFF5E35B1.toInt(),
            listOf(0xFFD1C4E9, 0xFFB3E5FC, 0xFFFFF59D, 0xFFF8BBD0).map { it.toInt() }),
        Palette("Soleil", "🌻", 0xFFFFA000.toInt(), 0xFFFFF8E1.toInt(), 0xFFEF6C00.toInt(),
            listOf(0xFFFFE082, 0xFFFFCCBC, 0xFFDCEDC8, 0xFFB3E5FC).map { it.toInt() }),
        Palette("Licorne", "🦄", 0xFFAB47BC.toInt(), 0xFFFCE4EC.toInt(), 0xFF8E24AA.toInt(),
            listOf(0xFFE1BEE7, 0xFFF8BBD0, 0xFFB2EBF2, 0xFFFFF9C4).map { it.toInt() }),
        Palette("Volcan", "🌋", 0xFFE64A19.toInt(), 0xFFFFE0B2.toInt(), 0xFFBF360C.toInt(),
            listOf(0xFFFFCCBC, 0xFFFFE082, 0xFFD7CCC8, 0xFFFFAB91).map { it.toInt() }),
        Palette("Menthe", "🍃", 0xFF00897B.toInt(), 0xFFE0F2F1.toInt(), 0xFF00695C.toInt(),
            listOf(0xFFB2DFDB, 0xFFDCEDC8, 0xFFFFF9C4, 0xFFB3E5FC).map { it.toInt() }),
    )

    /** Couleurs libres proposées à l'enfant. */
    val swatches = listOf(
        0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5,
        0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFB8C00,
        0xFFF4511E, 0xFF6D4C41, 0xFF546E7A, 0xFF212121,
    ).map { it.toInt() }

    fun current(c: Context): Palette {
        val custom = Store.customColor(c)
        if (custom != 0) return derive(custom)
        return list.getOrElse(Store.themeIndex(c)) { list[0] }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** Construit une ambiance complète à partir d'une seule couleur. */
    fun derive(color: Int): Palette {
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        fun hue(shift: Float) = Color.HSVToColor(floatArrayOf((hsv[0] + shift) % 360f, 0.35f, 1f))
        return Palette(
            "Ma couleur", "🎨", color, mix(color, Color.WHITE, 0.85f), mix(color, Color.BLACK, 0.2f),
            listOf(hue(0f), hue(60f), hue(180f), hue(300f))
        )
    }

    fun background(p: Palette) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.top, p.bottom))

    /** Texte lisible (noir ou blanc) sur une couleur donnée. */
    fun onColor(color: Int): Int {
        val lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return if (lum > 0.6) 0xFF1C1C1C.toInt() else Color.WHITE
    }
}

/** Bouton à maintenir appuyé (évite les déclenchements involontaires du SOS). */
object Hold {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View, millis: Long, onHint: () -> Unit, onFire: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var fired = false
        val fire = Runnable {
            fired = true
            vibrate(view.context, 300)
            view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            onFire()
        }
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    vibrate(v.context, 30)
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(millis).start()
                    handler.postDelayed(fire, millis)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(fire)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    if (!fired && e.action == MotionEvent.ACTION_UP) onHint()
                }
            }
            true
        }
    }

    fun vibrate(c: Context, ms: Long) {
        try {
            c.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}
