package org.fossify.gallery.extensions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.google.android.material.materialswitch.MaterialSwitch
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDynamicTheme
import org.fossify.gallery.R

private const val SELECTED_SCALE = 0.88f
private const val SELECTION_ANIM_MS = 180L

private class RoundedOutlineProvider(var radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

fun Context.getTonalSurfaceColor(): Int {
    return if (isDynamicTheme()) {
        resources.getColor(org.fossify.commons.R.color.you_dialog_background_color, theme)
    } else {
        ColorUtils.blendARGB(getProperBackgroundColor(), getProperPrimaryColor(), 0.10f)
    }
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

/**
 * Turns consecutive settings rows into Material 3 Expressive segmented tonal groups:
 * large outer corners, small inner corners and a thin gap between rows.
 */
fun ViewGroup.applyExpressiveSettingsGroups() {
    val ctx = context
    val density = resources.displayMetrics.density
    val tone = ctx.getTonalSurfaceColor()
    val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(ctx.getProperTextColor(), 32))
    val outer = 24f * density
    val inner = 6f * density
    val side = (16f * density).toInt()
    val gap = (2f * density).toInt()
    val labelStart = (28f * density).toInt()
    val group = ArrayList<View>()

    fun flush() {
        group.forEachIndexed { index, row ->
            val first = index == 0
            val last = index == group.lastIndex
            val top = if (first) outer else inner
            val bottom = if (last) outer else inner
            val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            val shape = GradientDrawable().apply {
                setColor(tone)
                cornerRadii = radii
            }
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = radii
            }
            row.background = RippleDrawable(ripple, shape, mask)
            (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.setMargins(side, if (first) 0 else gap, side, 0)
                row.layoutParams = lp
            }
        }
        group.clear()
    }

    for (i in 0 until childCount) {
        val child = getChildAt(i)
        when {
            child is TextView -> {
                flush()
                child.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                child.letterSpacing = 0.02f
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.marginStart = labelStart
                    child.layoutParams = lp
                }
            }

            child is ImageView -> {
                flush()
                child.visibility = View.GONE
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
