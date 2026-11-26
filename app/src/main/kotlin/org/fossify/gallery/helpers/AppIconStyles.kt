package org.fossify.gallery.helpers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.gallery.R
import org.fossify.gallery.activities.SplashActivity
import org.fossify.gallery.extensions.config

/**
 * Launcher icon designs on top of the color icons of the commons library. Every design is an activity-alias in the
 * manifest, exactly one launcher entry is enabled at a time.
 */
object AppIconStyles {
    const val CLASSIC = 0
    const val COUNT = 6

    // same order as the app icon colors, the library switches the color icons by index
    private val colorAliases = listOf(
        "Red", "Pink", "Purple", "Deep_purple", "Indigo", "Blue", "Light_blue", "Cyan", "Teal", "Green",
        "Light_green", "Lime", "Yellow", "Amber", "Orange", "Deep_orange", "Brown", "Blue_grey", "Grey_black"
    )

    val previews = intArrayOf(
        R.mipmap.ic_launcher_style1,
        R.mipmap.ic_launcher_style2,
        R.mipmap.ic_launcher_style3,
        R.mipmap.ic_launcher_style4,
        R.mipmap.ic_launcher_style5,
        R.mipmap.ic_launcher_style6
    )

    val names = intArrayOf(
        R.string.app_icon_style_1,
        R.string.app_icon_style_2,
        R.string.app_icon_style_3,
        R.string.app_icon_style_4,
        R.string.app_icon_style_5,
        R.string.app_icon_style_6
    )

    /** [style] 0 brings back the color icon, 1..[COUNT] switches to that design. */
    fun apply(context: Context, style: Int) {
        val safeStyle = if (style in 1..COUNT) style else CLASSIC
        context.config.appIconStyle = safeStyle

        val colors = context.resources.getIntArray(org.fossify.commons.R.array.md_app_icon_colors)
        val colorIndex = colors.indexOf(context.baseConfig.appIconColor)
        val target = if (safeStyle == CLASSIC) {
            colorAliases.getOrElse(colorIndex) { "Green" }
        } else {
            "Style$safeStyle"
        }

        // the new entry goes first so the launcher never ends up without one
        setEnabled(context, target, true)
        val all = colorAliases + (1..COUNT).map { "Style$it" }
        all.filter { it != target }.forEach { setEnabled(context, it, false) }
    }

    fun currentStyle(context: Context) = context.config.appIconStyle

    /** Keeps a chosen design when the library switches the color icon on its own. */
    fun ensureApplied(context: Context) {
        val style = context.config.appIconStyle
        if (style in 1..COUNT) {
            apply(context, style)
        }
    }

    private fun setEnabled(context: Context, alias: String, enabled: Boolean) {
        try {
            val component = ComponentName(context.packageName, "${SplashActivity::class.java.name}.$alias")
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (ignored: Exception) {
        }
    }
}
