package fr.promeneur.telenfant

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Petits outils pour construire l'interface en code (aucune dépendance). */
object Ui {
    const val GREEN = 0xFF2E7D32.toInt()
    const val RED = 0xFFC62828.toInt()
    const val BLUE = 0xFF1565C0.toInt()
    const val GREY = 0xFF607D8B.toInt()
    const val BG = 0xFFF4F7F4.toInt()
    const val TEXT = 0xFF1C1C1C.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    fun text(c: Context, s: String, size: Float = 18f, color: Int = TEXT, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(c: Context, label: String, color: Int, size: Float = 18f, onClick: () -> Unit) =
        Button(c).apply {
            text = label
            textSize = size
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(color, dp(c, 14).toFloat())
            setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
            setOnClickListener { onClick() }
        }

    fun vertical(c: Context, padding: Int = 16) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, padding)
        setPadding(p, p, p, p)
    }

    fun horizontal(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0, c: Context? = null) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            if (c != null) setMargins(dp(c, margin), dp(c, margin), dp(c, margin), dp(c, margin))
        }

    fun space(c: Context, h: Int) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
    }

    fun pinField(c: Context, hint: String) = EditText(c).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        textSize = 22f
        gravity = Gravity.CENTER
    }

    /** Demande le code admin puis exécute onOk s'il est correct. */
    fun askPin(a: Activity, onOk: () -> Unit) {
        val field = pinField(a, "Code admin")
        AlertDialog.Builder(a)
            .setTitle("Accès parent")
            .setView(field)
            .setPositiveButton("Valider") { _, _ ->
                if (Store.checkPin(a, field.text.toString())) onOk()
                else android.widget.Toast.makeText(a, "Code incorrect", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Création / changement du code admin (4 à 8 chiffres, saisi deux fois). */
    fun createPin(a: Activity, cancellable: Boolean, onDone: () -> Unit) {
        val box = vertical(a, 16)
        val f1 = pinField(a, "Nouveau code (4 à 8 chiffres)")
        val f2 = pinField(a, "Confirmer le code")
        box.addView(f1)
        box.addView(f2)
        val dlg = AlertDialog.Builder(a)
            .setTitle("Code admin parent")
            .setMessage("Ce code protège les réglages et la désactivation du bridage. Ne le communiquez pas à l'enfant.")
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setCancelable(cancellable)
            .apply { if (cancellable) setNegativeButton("Annuler", null) }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = f1.text.toString()
                val p2 = f2.text.toString()
                when {
                    p1.length !in 4..8 -> { f1.error = "4 à 8 chiffres" }
                    p1 != p2 -> { f2.error = "Les codes ne correspondent pas" }
                    else -> {
                        Store.setPin(a, p1)
                        dlg.dismiss()
                        onDone()
                    }
                }
            }
        }
        dlg.show()
    }
}
