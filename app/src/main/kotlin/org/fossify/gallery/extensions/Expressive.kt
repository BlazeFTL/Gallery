package org.fossify.gallery.extensions

import android.annotation.SuppressLint
import android.app.Activity
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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import org.fossify.commons.extensions.getDialogBackgroundColor
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

/** Colors a screen is (or is about to be) drawn with. Lets previews pass colors that are not saved yet. */
class ExpressiveColors(val background: Int, val primary: Int, val text: Int, val dynamic: Boolean)

private fun Context.defaultExpressiveColors() =
    ExpressiveColors(getProperBackgroundColor(), getProperPrimaryColor(), getProperTextColor(), isDynamicTheme())

private fun Context.isLightBackground() = ColorUtils.calculateLuminance(getProperBackgroundColor()) > 0.5

/** page = screen background, tile = card surface that sits on top of it */
private fun Context.getSettingsPalette(colors: ExpressiveColors): Pair<Int, Int> {
    val bg = colors.background
    val primary = colors.primary
    return if (ColorUtils.calculateLuminance(bg) > 0.5) {
        val page = if (colors.dynamic) {
            resources.getColor(org.fossify.commons.R.color.you_dialog_background_color, theme)
        } else {
            ColorUtils.blendARGB(bg, primary, 0.08f)
        }
        Pair(page, bg)
    } else {
        Pair(bg, ColorUtils.blendARGB(bg, primary, 0.12f))
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

/** Replaces the stock overflow popup with a themed Expressive one and shows icons in the fallback popup. */
fun Toolbar.applyExpressivePopup() {
    menu.enableExpressiveIcons(context)
    installExpressiveOverflow { menu }
}

/** Same theme-aware popup for everything that reads it from the activity theme (action mode, context menus). */
fun Activity.applyExpressivePopupTheme() {
    if (isDynamicTheme()) return
    theme.applyStyle(
        if (isLightBackground()) R.style.ThemeOverlay_Gallery_LightPopup else R.style.ThemeOverlay_Gallery_DarkPopup,
        true
    )
}

/** Viewer toolbars use the same themed popup as everything else. */
fun Toolbar.applyExpressiveDarkMenu() = applyExpressivePopup()

/** Action mode (selection) toolbar overflow. Call from prepareActionMode. */
fun Activity.installExpressiveActionModeOverflow(menu: Menu) {
    val bar = findViewById<ViewGroup>(androidx.appcompat.R.id.action_mode_bar) ?: return
    bar.post { bar.installExpressiveOverflow { menu } }
}

/**
 * Material 3 Expressive settings: tonal page, white segmented cards with large outer corners,
 * per-section coloured icon badges, trailing chevrons and emphasised titles.
 */
fun ViewGroup.applyExpressiveSettingsGroups(colors: ExpressiveColors = context.defaultExpressiveColors()) {
    val ctx = context
    val density = resources.displayMetrics.density
    fun dp(v: Float) = (v * density).toInt()

    val (page, tile) = ctx.getSettingsPalette(colors)
    val light = ColorUtils.calculateLuminance(colors.background) > 0.5
    val textColor = colors.text
    val accentText = ColorUtils.blendARGB(colors.primary, textColor, 0.45f)
    val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 32))
    val outer = dp(20f).toFloat()
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

        val valueLp = texts.getOrNull(1)?.layoutParams as? RelativeLayout.LayoutParams
        val inlineValue = texts.size == 2 && row is RelativeLayout && valueLp != null && valueLp.getRule(RelativeLayout.BELOW) == 0
        val singleLine = texts.size == 1 && texts[0] !is CompoundButton
        if ((singleLine || inlineValue) && (row is RelativeLayout || row is ConstraintLayout)) {
            row.setPaddingRelative(row.paddingStart, dp(12f), row.paddingEnd, dp(12f))
            row.minimumHeight = dp(64f)
            texts.take(if (inlineValue) 2 else 1).forEach { tv ->
                when (val lp = tv.layoutParams) {
                    is RelativeLayout.LayoutParams -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        tv.layoutParams = lp
                    }

                    is ConstraintLayout.LayoutParams -> {
                        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        tv.layoutParams = lp
                    }
                }
            }
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
 * Clean Expressive choice list for dialogs: idle rows have no container, the checked row gets a
 * primary-tinted pill. Pass the dialog root (usually the ScrollView) before it is shown.
 */
fun ViewGroup.applyExpressiveDialogTiles() {
    val ctx = context
    val content = when (this) {
        is ScrollView, is NestedScrollView -> getChildAt(0) as? ViewGroup ?: return
        else -> this
    }

    val density = resources.displayMetrics.density
    fun dp(v: Float) = (v * density).toInt()
    val surface = ctx.getDialogBackgroundColor()
    val textColor = ctx.getProperTextColor()
    val chosen = ColorUtils.blendARGB(surface, ctx.getProperPrimaryColor(), 0.24f)
    val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 32))
    val radii = FloatArray(8) { dp(26f).toFloat() }
    val pending = ArrayList<View>()

    fun flush() {
        pending.forEachIndexed { index, row ->
            val states = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), roundedShape(chosen, radii))
                addState(intArrayOf(), roundedShape(Color.TRANSPARENT, radii))
            }
            row.background = RippleDrawable(ripple, states, roundedShape(Color.WHITE, radii))
            row.minimumHeight = dp(52f)
            row.setPaddingRelative(dp(12f), row.paddingTop, dp(16f), row.paddingBottom)
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

private class PopupPalette(val surface: Int, val text: Int, val icon: Int, val ripple: Int)

private fun Context.popupPalette(): PopupPalette {
    val bg = getProperBackgroundColor()
    val primary = getProperPrimaryColor()
    val light = ColorUtils.calculateLuminance(bg) > 0.5
    val surface: Int
    val text: Int
    if (isDynamicTheme() && light) {
        surface = resources.getColor(org.fossify.commons.R.color.you_dialog_background_color, theme)
        text = resources.getColor(org.fossify.commons.R.color.you_neutral_text_color, theme)
    } else {
        surface = ColorUtils.blendARGB(bg, primary, if (light) 0.06f else 0.16f)
        text = getProperTextColor()
    }
    return PopupPalette(surface, text, ColorUtils.setAlphaComponent(text, 230), ColorUtils.setAlphaComponent(text, 32))
}

private fun ViewGroup.findOverflowButton(): View? {
    val description = context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
    for (i in 0 until childCount) {
        val v = getChildAt(i)
        if (v is ImageView && v.contentDescription?.toString() == description) return v
        if (v is ViewGroup) v.findOverflowButton()?.let { return it }
    }
    return null
}

/**
 * Takes over the overflow (3 dot) button of a toolbar or action mode bar and opens the Expressive popup
 * instead of the stock one. If the button cannot be found the stock popup keeps working.
 */
@SuppressLint("RestrictedApi")
fun ViewGroup.installExpressiveOverflow(menuProvider: () -> Menu?) {
    fun tryInstall(): Boolean {
        val button = findOverflowButton() ?: return false
        button.setOnClickListener { anchor ->
            val owner = menuProvider() as? MenuBuilder ?: return@setOnClickListener
            val items = owner.nonActionItems.filter { it.isVisible }
            if (items.isNotEmpty()) {
                anchor.context.showExpressiveMenu(anchor, owner, items, null, null)
            }
        }
        return true
    }

    if (!tryInstall()) {
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (tryInstall()) removeOnLayoutChangeListener(this)
            }
        })
    }
}

@SuppressLint("RestrictedApi")
private fun Context.showExpressiveMenu(
    anchor: View,
    owner: MenuBuilder,
    items: List<MenuItem>,
    header: CharSequence?,
    onBack: (() -> Unit)?
) {
    val density = resources.displayMetrics.density
    fun dp(v: Float) = (v * density).toInt()
    val palette = popupPalette()

    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        minimumWidth = dp(224f)
    }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    val popup = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    popup.setBackgroundDrawable(GradientDrawable().apply {
        setColor(palette.surface)
        cornerRadius = dp(24f).toFloat()
    })
    popup.elevation = dp(8f).toFloat()
    popup.isOutsideTouchable = true

    val hasIcons = items.any { it.icon != null }

    fun addRow(title: CharSequence, icon: android.graphics.drawable.Drawable?, leadingRes: Int, trailingRes: Int,
               enabled: Boolean, bold: Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPaddingRelative(dp(16f), 0, dp(16f), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(palette.ripple),
                null,
                roundedShape(Color.WHITE, FloatArray(8) { dp(16f).toFloat() })
            )
            alpha = if (enabled) 1f else 0.38f
            isEnabled = enabled
            setOnClickListener { if (enabled) onClick() }
        }
        val leading: android.graphics.drawable.Drawable? = when {
            leadingRes != 0 -> AppCompatResources.getDrawable(this, leadingRes)?.mutate()
            else -> icon?.constantState?.newDrawable()?.mutate() ?: icon?.mutate()
        }
        if (leading != null) {
            leading.setTint(palette.icon)
            row.addView(ImageView(this).apply { setImageDrawable(leading) }, LinearLayout.LayoutParams(dp(24f), dp(24f)))
        } else if (hasIcons) {
            row.addView(View(this), LinearLayout.LayoutParams(dp(24f), dp(24f)))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(palette.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setMediumWeight()
            setPaddingRelative(if (leading != null || hasIcons) dp(16f) else 0, 0, dp(12f), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailingRes != 0) {
            val trailing = AppCompatResources.getDrawable(this, trailingRes)?.mutate()?.also { it.setTint(palette.icon) }
            row.addView(ImageView(this).apply { setImageDrawable(trailing) }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
        }
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (list.childCount > 0) topMargin = dp(2f)
        })
    }

    if (header != null) {
        addRow(header, null, R.drawable.ic_arrow_left_vector, 0, true, true) {
            popup.dismiss()
            onBack?.invoke()
        }
    }

    items.forEach { item ->
        val sub = item.subMenu
        val trailing = when {
            sub != null -> R.drawable.ic_chevron_right_vector
            item.isCheckable && item.isChecked -> R.drawable.ic_check_vector
            else -> 0
        }
        addRow(item.title ?: "", item.icon, 0, trailing, item.isEnabled, false) {
            popup.dismiss()
            if (sub is MenuBuilder) {
                val subItems = (0 until sub.size()).map { sub.getItem(it) }.filter { it.isVisible }
                showExpressiveMenu(anchor, sub, subItems, item.title) {
                    showExpressiveMenu(anchor, owner, items, header, onBack)
                }
            } else {
                owner.performItemAction(item, 0)
            }
        }
    }

    val maxHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
    scroll.measure(
        View.MeasureSpec.makeMeasureSpec(dp(300f), View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
    )
    popup.width = scroll.measuredWidth
    popup.height = minOf(scroll.measuredHeight, maxHeight)
    popup.showAsDropDown(anchor, -dp(8f), -anchor.height + dp(4f), Gravity.END)

    scroll.alpha = 0f
    scroll.scaleX = 0.9f
    scroll.scaleY = 0.9f
    scroll.pivotX = popup.width.toFloat()
    scroll.pivotY = 0f
    scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L).start()
}
