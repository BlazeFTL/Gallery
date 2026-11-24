package org.fossify.gallery.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.materialswitch.MaterialSwitch
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDynamicTheme
import org.fossify.gallery.R

private const val SELECTED_SCALE = 0.88f
private const val SELECTION_ANIM_MS = 180L
private val SECTION_HUES = floatArrayOf(265f, 215f, 145f, 35f, 180f, 335f, 5f, 55f)

private class RoundedOutlineProvider(var radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

private fun Context.isLightBackground() = ColorUtils.calculateLuminance(getProperBackgroundColor()) > 0.5

/** page = screen background, tile = card surface that sits on top of it */
private fun Context.getSettingsPalette(): Pair<Int, Int> {
    val bg = getProperBackgroundColor()
    val primary = getProperPrimaryColor()
    return if (isLightBackground()) {
        val page = if (isDynamicTheme()) {
            resources.getColor(org.fossify.commons.R.color.you_dialog_background_color, theme)
        } else {
            ColorUtils.blendARGB(bg, primary, 0.08f)
        }
        Pair(page, bg)
    } else {
        Pair(bg, ColorUtils.blendARGB(bg, primary, 0.12f))
    }
}

private fun Context.getDialogTileColor(): Int {
    return if (isDynamicTheme()) {
        resources.getColor(org.fossify.commons.R.color.you_background_color, theme)
    } else {
        ColorUtils.blendARGB(getProperBackgroundColor(), getProperPrimaryColor(), 0.12f)
    }
}

private fun badgeColors(hue: Float, light: Boolean): Pair<Int, Int> {
    val container = ColorUtils.HSLToColor(floatArrayOf(hue, if (light) 0.65f else 0.35f, if (light) 0.90f else 0.30f))
    val icon = ColorUtils.HSLToColor(floatArrayOf(hue, if (light) 0.50f else 0.75f, if (light) 0.28f else 0.85f))
    return Pair(container, icon)
}

private fun TextView.setMediumWeight() {
    typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(typeface, 500, false)
    } else {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

private fun groupedRadii(first: Boolean, last: Boolean, outer: Float, inner: Float): FloatArray {
    val top = if (first) outer else inner
    val bottom = if (last) outer else inner
    return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
}

private fun roundedShape(color: Int, radii: FloatArray) = GradientDrawable().apply {
    setColor(color)
    cornerRadii = radii
}

/**
 * Material 3 Expressive selection morph: the tile shrinks and its corners grow while selected.
 * [key] identifies the bound item so recycled views snap to state instead of animating.
 */
fun View.setExpressiveSelected(selected: Boolean, key: Any?, idleRadiusPx: Float, selectedRadiusPx: Float) {
    val provider = outlineProvider as? RoundedOutlineProvider ?: RoundedOutlineProvider(idleRadiusPx).also {
        outlineProvider = it
        clipToOutline = true
    }

    val targetScale = if (selected) SELECTED_SCALE else 1f
    val sameItem = getTag(R.id.expressive_selection_key) == key
    setTag(R.id.expressive_selection_key, key)

    animate().cancel()
    provider.radius = if (selected) selectedRadiusPx else idleRadiusPx
    invalidateOutline()

    if (sameItem && isAttachedToWindow) {
        animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(SELECTION_ANIM_MS)
            .start()
    } else {
        scaleX = targetScale
        scaleY = targetScale
    }
}

/** Shows icons next to overflow entries. Icons of overflow-only items get [tint]. */
@SuppressLint("RestrictedApi")
fun Menu.enableExpressiveIcons(context: Context, tint: Int = context.getProperTextColor()) {
    (this as? MenuBuilder)?.setOptionalIconsVisible(true)
    for (i in 0 until size()) {
        val item = getItem(i)
        val overflowOnly = (item as? MenuItemImpl)?.let { !it.requiresActionButton() && !it.requestsActionButton() } == true
        if (overflowOnly) {
            item.icon?.mutate()?.setTint(tint)
        }
    }
}

/** Dark rounded popup with white icons, for toolbars drawn over the black viewer. */
fun Toolbar.applyExpressiveDarkMenu() {
    popupTheme = R.style.ThemeOverlay_Gallery_DarkPopup
    menu.enableExpressiveIcons(context, Color.WHITE)
}

/**
 * Material 3 Expressive settings: tonal page, white segmented cards with large outer corners,
 * per-section coloured icon badges, trailing chevrons and emphasised titles.
 */
fun ViewGroup.applyExpressiveSettingsGroups() {
    val ctx = context
    val density = resources.displayMetrics.density
    fun dp(v: Float) = (v * density).toInt()

    val (page, tile) = ctx.getSettingsPalette()
    val light = ctx.isLightBackground()
    val textColor = ctx.getProperTextColor()
    val accentText = ColorUtils.blendARGB(ctx.getProperPrimaryColor(), textColor, 0.45f)
    val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 32))
    val outer = dp(28f).toFloat()
    val inner = dp(6f).toFloat()
    val side = dp(16f)
    val gap = dp(3f)
    val labelStart = dp(28f)
    val badgeSize = dp(40f)
    val badgeGap = dp(14f)
    val chevronSize = dp(24f)

    setBackgroundColor(page)
    (parent as? View)?.setBackgroundColor(page)

    var section = 0
    val group = ArrayList<View>()

    fun styleRow(row: View, first: Boolean, last: Boolean) {
        val radii = groupedRadii(first, last, outer, inner)
        row.background = RippleDrawable(ripple, roundedShape(tile, radii), roundedShape(Color.WHITE, radii))
        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.setMargins(side, if (first) 0 else gap, side, 0)
            row.layoutParams = lp
        }
        if (row !is ViewGroup) return

        val texts = ArrayList<TextView>()
        row.collectTextViews(texts)
        texts.getOrNull(0)?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setMediumWeight()
        }
        texts.getOrNull(1)?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accentText)
            alpha = 1f
        }

        val spec = SETTINGS_ROW_SPECS[row.id] ?: return
        if (row !is RelativeLayout && row !is ConstraintLayout) return

        val sign = if (row.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val (container, iconColor) = badgeColors(SECTION_HUES[section % SECTION_HUES.size], light)

        var badge = row.findViewById<ImageView>(R.id.expressive_badge)
        if (badge == null) {
            row.clipToPadding = false
            row.setPaddingRelative(
                row.paddingStart + badgeSize + badgeGap,
                row.paddingTop,
                row.paddingEnd + if (spec.chevron) dp(32f) else 0,
                row.paddingBottom
            )

            badge = ImageView(ctx).apply {
                id = R.id.expressive_badge
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setImageDrawable(AppCompatResources.getDrawable(ctx, spec.icon))
                translationX = -sign * (badgeSize + badgeGap)
            }
            row.addView(badge, row.edgeParams(badgeSize, start = true))

            if (spec.chevron) {
                val chevron = ImageView(ctx).apply {
                    id = R.id.expressive_chevron
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_chevron_right_vector))
                    translationX = sign * dp(32f)
                }
                row.addView(chevron, row.edgeParams(chevronSize, start = false))
            }
        }

        badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(container)
        }
        badge.setColorFilter(iconColor)
        row.findViewById<ImageView>(R.id.expressive_chevron)?.setColorFilter(ColorUtils.setAlphaComponent(textColor, 150))
    }

    fun flush() {
        group.forEachIndexed { index, row -> styleRow(row, index == 0, index == group.lastIndex) }
        group.clear()
    }

    for (i in 0 until childCount) {
        val child = getChildAt(i)
        when {
            child is TextView -> {
                flush()
                if (child.visibility == View.VISIBLE) {
                    section++
                }
                child.setMediumWeight()
                child.setTextColor(accentText)
                child.letterSpacing = 0.02f
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.marginStart = labelStart
                    child.layoutParams = lp
                }
            }

            child is ImageView -> {
                flush()
                child.layoutParams = child.layoutParams.apply { height = 0 }
            }

            child is ViewGroup && child.visibility == View.VISIBLE -> group.add(child)
        }
    }
    flush()

    if (!ctx.baseConfig.showCheckmarksOnSwitches) {
        forEachSwitch { switch ->
            switch.thumbIconDrawable = AppCompatResources.getDrawable(ctx, R.drawable.switch_thumb_icon)
        }
    }
}

/**
 * Turns the radio / checkbox rows of a choice dialog into segmented tonal tiles.
 * Pass the dialog root (usually the ScrollView) before it is shown.
 */
fun ViewGroup.applyExpressiveDialogTiles() {
    val ctx = context
    val content = when (this) {
        is ScrollView, is NestedScrollView -> getChildAt(0) as? ViewGroup ?: return
        else -> this
    }

    val density = resources.displayMetrics.density
    fun dp(v: Float) = (v * density).toInt()
    val tile = ctx.getDialogTileColor()
    val chosen = ColorUtils.blendARGB(tile, ctx.getProperPrimaryColor(), 0.28f)
    val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(ctx.getProperTextColor(), 32))
    val outer = dp(20f).toFloat()
    val inner = dp(6f).toFloat()
    val pending = ArrayList<View>()

    fun flush() {
        pending.forEachIndexed { index, row ->
            val radii = groupedRadii(index == 0, index == pending.lastIndex, outer, inner)
            val states = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), roundedShape(chosen, radii))
                addState(intArrayOf(), roundedShape(tile, radii))
            }
            row.background = RippleDrawable(ripple, states, roundedShape(Color.WHITE, radii))
            row.minimumHeight = dp(52f)
            row.setPaddingRelative(dp(14f), row.paddingTop, dp(16f), row.paddingBottom)
            (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = if (index == 0) 0 else dp(2f)
                lp.bottomMargin = 0
                row.layoutParams = lp
            }
        }
        pending.clear()
    }

    for (i in 0 until content.childCount) {
        val child = content.getChildAt(i)
        when {
            child is RadioGroup -> {
                flush()
                for (j in 0 until child.childCount) {
                    val button = child.getChildAt(j)
                    if (button.visibility == View.VISIBLE) pending.add(button)
                }
                flush()
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.bottomMargin = dp(12f)
                    child.layoutParams = it
                }
                child.setPadding(0, 0, 0, 0)
            }

            child is CompoundButton && child.visibility == View.VISIBLE -> pending.add(child)

            child is ImageView -> {
                flush()
                child.layoutParams = child.layoutParams.apply { height = 0 }
            }

            else -> flush()
        }
    }
    flush()
}

private fun ViewGroup.collectTextViews(out: MutableList<TextView>) {
    for (i in 0 until childCount) {
        val v = getChildAt(i)
        if (v is TextView && v.id != R.id.expressive_badge) {
            out.add(v)
        } else if (v is ViewGroup) {
            v.collectTextViews(out)
        }
    }
}

private fun ViewGroup.edgeParams(size: Int, start: Boolean): ViewGroup.LayoutParams {
    return when (this) {
        is RelativeLayout -> RelativeLayout.LayoutParams(size, size).apply {
            addRule(if (start) RelativeLayout.ALIGN_PARENT_START else RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.CENTER_VERTICAL)
        }

        else -> ConstraintLayout.LayoutParams(size, size).apply {
            if (start) startToStart = ConstraintLayout.LayoutParams.PARENT_ID else endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
    }
}

private fun ViewGroup.forEachSwitch(action: (MaterialSwitch) -> Unit) {
    for (i in 0 until childCount) {
        val v = getChildAt(i)
        if (v is MaterialSwitch) {
            action(v)
        } else if (v is ViewGroup) {
            v.forEachSwitch(action)
        }
    }
}
