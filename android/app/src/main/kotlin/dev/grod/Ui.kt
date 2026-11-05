package dev.grod

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * grod UI (ui/README.md) as plain Views: sections, list rows, key–value lines, buttons and a field.
 * Colors come from the generated grod_colors.xml, so light and dark follow the system. No shadows,
 * no badges; the sage accent is for the one "on" state of a screen.
 */
class Ui(private val context: Context) {
    private val res = context.resources

    fun dp(value: Int): Int = (value * res.displayMetrics.density).toInt()

    fun color(id: Int): Int = context.getColor(id)

    val strong: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD

    fun text(value: CharSequence, sizeSp: Float = 16f, colorId: Int = R.color.grod_foreground, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(colorId))
            setLineSpacing(0f, if (bold) 1.25f else 1.15f)
            if (bold) typeface = strong
        }

    fun muted(value: CharSequence, sizeSp: Float = 14f) = text(value, sizeSp, R.color.grod_muted_foreground)

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun box(fill: Int, stroke: Int?, radiusDp: Int) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(maxOf(1, dp(1)), color(it)) }
    }

    /** Muted uppercase title over a bordered surface; returns the body to fill with rows. */
    fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(
            text(title.uppercase(), 12f, R.color.grod_muted_foreground, bold = true).apply {
                letterSpacing = 0.06f
                setPadding(dp(13), dp(24), dp(13), dp(8))
            },
        )
        val body = column().apply { background = box(R.color.grod_surface, R.color.grod_border, 10) }
        parent.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return body
    }

    /** A list row: title, optional muted line, optional trailing view. Rows after the first get a divider. */
    fun row(body: LinearLayout, title: String, meta: CharSequence? = null, end: View? = null, off: Boolean = false, extra: View? = null) {
        if (body.childCount > 0) divider(body)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val main = column()
        main.addView(text(title, 16f, if (off) R.color.grod_muted_foreground else R.color.grod_foreground))
        if (meta != null) main.addView(muted(meta))
        if (extra != null) main.addView(extra)
        row.addView(main, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (end != null) {
            row.addView(end, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        body.addView(row)
    }

    /** Key–value line: muted key in a fixed column, value wrapping beside it. */
    fun kv(body: LinearLayout, key: String, value: CharSequence, valueColorId: Int = R.color.grod_foreground) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), if (body.childCount == 0) dp(12) else dp(4), dp(12), dp(4))
        }
        line.addView(muted(key, 15f), LinearLayout.LayoutParams(dp(128), WRAP_CONTENT))
        line.addView(text(value, 15f, valueColorId).apply { ellipsize = TextUtils.TruncateAt.END; maxLines = 3 },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(line)
    }

    /** Closes a key–value block with the section's bottom padding. */
    fun kvEnd(body: LinearLayout) = body.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, dp(8)))

    private fun divider(body: LinearLayout) =
        body.addView(View(context).apply { setBackgroundColor(color(R.color.grod_border)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))

    enum class Kind { PRIMARY, QUIET, GHOST }

    /** Variants differ by fill only, so two buttons side by side keep equal weight. */
    fun button(label: String, kind: Kind = Kind.QUIET, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = strong
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isClickable = true
        isFocusable = true
        when (kind) {
            Kind.PRIMARY -> {
                background = box(R.color.grod_primary, null, 6)
                setTextColor(color(R.color.grod_primary_foreground))
            }
            Kind.QUIET -> {
                background = box(R.color.grod_surface, R.color.grod_input, 6)
                setTextColor(color(R.color.grod_foreground))
            }
            Kind.GHOST -> {
                setTextColor(color(R.color.grod_foreground))
                setPadding(0, dp(8), 0, dp(8))
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        setOnClickListener { onClick() }
    }

    fun input(hint: String) = EditText(context).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(color(R.color.grod_foreground))
        setHintTextColor(color(R.color.grod_muted_foreground))
        background = box(R.color.grod_surface, R.color.grod_input, 6)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = true
    }
}
