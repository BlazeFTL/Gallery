package org.fossify.gallery.helpers

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.fossify.gallery.R
import org.fossify.gallery.activities.SplashActivity
import org.fossify.gallery.extensions.config

/**
 * Launcher icon designs. Every design is an activity-alias in the manifest, exactly one launcher entry is enabled
 * at a time. The icon never follows the theme colors: the Gallery icon is the default until another one is picked.
 */
object AppIconStyles {
    const val GALLERY = 0
    const val COUNT = 6
    const val MONOCHROME = 7

    /** Order shown in the picker. */
    val all = listOf(GALLERY, MONOCHROME) + (1..COUNT)

    private val allAliases = all.map { aliasOf(it) }

    private val stylePreviews = intArrayOf(
        R.mipmap.ic_launcher_style1,
        R.mipmap.ic_launcher_style2,
        R.mipmap.ic_launcher_style3,
        R.mipmap.ic_launcher_style4,
        R.mipmap.ic_launcher_style5,
        R.mipmap.ic_launcher_style6
    )

    private val styleNames = intArrayOf(
        R.string.app_icon_style_1,
        R.string.app_icon_style_2,
        R.string.app_icon_style_3,
        R.string.app_icon_style_4,
        R.string.app_icon_style_5,
        R.string.app_icon_style_6
    )

    fun previewOf(style: Int): Int = when {
        style == MONOCHROME -> R.mipmap.ic_launcher_mono
        style in 1..COUNT -> stylePreviews[style - 1]
        else -> R.mipmap.ic_launcher
    }

    fun nameOf(style: Int): Int = when {
        style == MONOCHROME -> R.string.app_icon_monochrome
        style in 1..COUNT -> styleNames[style - 1]
        else -> R.string.app_icon_gallery
    }

    private fun aliasOf(style: Int): String = when {
        style == MONOCHROME -> "Mono"
        style in 1..COUNT -> "Style$style"
        else -> "Gallery"
    }

    private fun normalize(style: Int) = if (style == MONOCHROME || style in 1..COUNT) style else GALLERY

    /** Switches the launcher to the given design. */
    fun apply(context: Context, style: Int) {
        val safeStyle = normalize(style)
        context.config.appIconStyle = safeStyle

        val target = aliasOf(safeStyle)
        // the new entry goes first so the launcher never ends up without one
        setEnabled(context, target, true)
        allAliases.filter { it != target }.forEach { setEnabled(context, it, false) }
    }

    fun currentStyle(context: Context) = normalize(context.config.appIconStyle)

    /** Makes sure only the chosen design is enabled. */
    fun ensureApplied(context: Context) = apply(context, context.config.appIconStyle)

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
