package com.screenshare.send

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Colours and small view builders for the app's one screen. There is no layout XML and no library. */
internal object Palette {
    const val BG = 0xFF241432.toInt()
    const val SURFACE = 0xFF31203F.toInt()
    const val SURFACE_HIGH = 0xFF432D55.toInt()
    const val TEXT = 0xFFFFF3EA.toInt()
    const val MUTED = 0xFFCDBBD8.toInt()
    const val ACCENT = 0xFFFFC53D.toInt()
    const val ON_ACCENT = 0xFF2A1600.toInt()
    const val DIVIDER = 0x1FFFFFFF
    const val RIPPLE_ON_DARK = 0x33FFFFFF
    const val RIPPLE_ON_ACCENT = 0x33000000
}

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

private fun Context.rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

private fun Context.ripple(content: Drawable?, rippleColor: Int, radiusDp: Int) =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, rounded(Color.WHITE, radiusDp))

internal enum class ButtonStyle { PRIMARY, SECONDARY, TEXT }

internal fun Context.actionButton(label: String, style: ButtonStyle, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minWidth = 0; minimumWidth = 0
        when (style) {
            ButtonStyle.PRIMARY -> {
                setTextColor(Palette.ON_ACCENT)
                background = ripple(rounded(Palette.ACCENT, 16), Palette.RIPPLE_ON_ACCENT, 16)
                minHeight = dp(56); minimumHeight = dp(56)
                setPadding(dp(20), 0, dp(20), 0)
            }
            ButtonStyle.SECONDARY -> {
                setTextColor(Palette.TEXT)
                background = ripple(rounded(Palette.SURFACE_HIGH, 16), Palette.RIPPLE_ON_DARK, 16)
                minHeight = dp(52); minimumHeight = dp(52)
                setPadding(dp(20), 0, dp(20), 0)
            }
            ButtonStyle.TEXT -> {
                setTextColor(Palette.ACCENT)
                textSize = 15f
                background = ripple(null, Palette.RIPPLE_ON_DARK, 12)
                minHeight = dp(48); minimumHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
            }
        }
        setOnClickListener { onClick() }
    }

/** A tappable list row: a title on the left, and a value or a switch on the right. */
internal class Row(val view: LinearLayout, val value: TextView, val switch: Switch?)

internal fun Context.listRow(title: String, withSwitch: Boolean = false, onClick: () -> Unit): Row {
    val value = TextView(this).apply {
        textSize = 14f
        setTextColor(Palette.MUTED)
        gravity = Gravity.END
    }
    val sw = if (!withSwitch) null else Switch(this).apply {
        // The row handles the tap, so the switch only shows the state.
        isClickable = false
        isFocusable = false
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.ACCENT, 0xFFD9CCE2.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x99FFC53D.toInt(), 0x55FFFFFF))
    }
    val view = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE_ON_DARK), null,
            GradientDrawable().apply { setColor(Color.WHITE) })
        isClickable = true
        isFocusable = true
        addView(TextView(this@listRow).apply {
            text = title
            textSize = 16f
            setTextColor(Palette.TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(sw ?: value, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        setOnClickListener { onClick() }
    }
    return Row(view, value, sw)
}

/** Rows grouped on one rounded surface with hairline dividers, like the phone's own settings. */
internal fun Context.listGroup(vararg rows: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.SURFACE, 20)
    clipToOutline = true
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = InsetDrawable(GradientDrawable().apply {
        setColor(Palette.DIVIDER)
        setSize(0, 1)
    }, dp(16), 0, 0, 0)
    rows.forEach { addView(it) }
}

internal fun Context.sectionLabel(label: String): TextView = TextView(this).apply {
    text = label
    textSize = 14f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Palette.MUTED)
    setPadding(dp(16), dp(28), dp(16), dp(8))
}

/** A panel for the display status at the top. */
internal fun Context.panel(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.SURFACE, 20)
    setPadding(dp(20), dp(16), dp(12), dp(8))
}

/** A vertical column that stops growing at [maxWidthPx], so the screen stays readable sideways. */
internal class Column(context: Context, private val maxWidthPx: Int) : LinearLayout(context) {
    init { orientation = VERTICAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (w > maxWidthPx) MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}

internal var View.shown: Boolean
    get() = visibility == View.VISIBLE
    set(v) { visibility = if (v) View.VISIBLE else View.GONE }
