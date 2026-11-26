package org.fossify.gallery.dialogs

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R
import org.fossify.gallery.helpers.AppIconStyles

/** Lets the user pick one of the launcher icon designs, or go back to the classic color icon. */
class AppIconStyleDialog(
    private val activity: BaseSimpleActivity,
    private val selectedStyle: Int,
    private val onClassic: () -> Unit,
    private val onStyle: (Int) -> Unit
) {
    private var dialog: AlertDialog? = null
    private val density = activity.resources.displayMetrics.density
    private val textColor = activity.getProperTextColor()
    private val primaryColor = activity.getProperPrimaryColor()

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }

        content.addView(TextView(activity).apply {
            setText(R.string.app_icon_style_title)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        content.addView(buildClassicRow())

        val names = AppIconStyles.names
        var row: LinearLayout? = null
        for (index in 0 until AppIconStyles.COUNT) {
            if (index % 3 == 0) {
                row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(12) }
                }
                content.addView(row)
            }
            row?.addView(buildTile(index + 1, activity.getString(names[index])))
        }

        val scroll = ScrollView(activity).apply { addView(content) }
        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(scroll, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun tileBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(if (selected) ColorUtils.setAlphaComponent(primaryColor, 56) else ColorUtils.setAlphaComponent(textColor, 16))
        if (selected) {
            setStroke(dp(2), primaryColor)
        }
    }

    private fun previewOf(drawableRes: Int, sizeDp: Int): ImageView {
        return ImageView(activity).apply {
            setImageDrawable(activity.getDrawable(drawableRes))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun buildClassicRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(10))
            background = tileBackground(selectedStyle == AppIconStyles.CLASSIC)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog?.dismiss()
                onClassic()
            }
        }

        row.addView(previewOf(R.mipmap.ic_launcher, 52))
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(TextView(activity).apply {
            setText(R.string.app_icon_style_classic)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(activity).apply {
            setText(R.string.app_icon_style_classic_hint)
            setTextColor(ColorUtils.setAlphaComponent(textColor, 160))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        row.addView(texts)
        return row
    }

    private fun buildTile(style: Int, name: String): View {
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = tileBackground(selectedStyle == style)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener {
                dialog?.dismiss()
                onStyle(style)
            }
        }

        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        frame.addView(previewOf(AppIconStyles.previews[style - 1], 64))
        tile.addView(frame)
        tile.addView(TextView(activity).apply {
            text = name
            setTextColor(if (selectedStyle == style) primaryColor else textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
        })
        return tile
    }
}
