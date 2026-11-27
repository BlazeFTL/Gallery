package org.fossify.gallery.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityAboutBinding
import org.fossify.gallery.extensions.applyExpressiveSettingsGroups

class AboutActivity : SimpleActivity() {
    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val AUTHOR_URL = "https://github.com/BlazeFTL"
        private const val REPO_URL = "https://github.com/BlazeFTL/Gallery"
        private const val ISSUES_URL = "$REPO_URL/issues"
        private const val RELEASES_URL = "$REPO_URL/releases"
        private const val LICENSE_URL = "$REPO_URL/blob/main/LICENSE"

        private val LIBRARIES = listOf(
            "Simple Gallery" to "https://github.com/SimpleMobileTools/Simple-Gallery",
            "Fossify Gallery" to "https://github.com/FossifyOrg/Gallery",
            "Fossify Commons" to "https://github.com/FossifyOrg/Commons",
            "AndroidX and Material Components" to "https://developer.android.com/jetpack/androidx",
            "Glide" to "https://github.com/bumptech/glide",
            "Picasso" to "https://github.com/square/picasso",
            "Android Image Cropper" to "https://github.com/vanniktech/Android-Image-Cropper",
            "Subsampling Scale Image View" to "https://github.com/davemorrissey/subsampling-scale-image-view",
            "GestureViews" to "https://github.com/alexvasilkov/GestureViews",
            "Media3 ExoPlayer" to "https://github.com/androidx/media",
            "Android GIF Drawable" to "https://github.com/koral--/android-gif-drawable",
            "APNG4Android" to "https://github.com/penfeizhou/APNG4Android",
            "GlideWebpDecoder" to "https://github.com/zjupure/GlideWebpDecoder",
            "JXL Coder" to "https://github.com/awxkee/jxl-coder",
            "AndroidSVG" to "https://github.com/BigBadaboom/androidsvg",
            "Android Photo Filters" to "https://github.com/Zomato/AndroidPhotoFilters",
            "Apache Commons Imaging" to "https://commons.apache.org/proper/commons-imaging/"
        )
    }

    private val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.aboutAppbar),
            padBottomSystem = listOf(binding.aboutNestedScrollview)
        )
        setupMaterialScrollListener(binding.aboutNestedScrollview, binding.aboutAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
        setupHolder()
        setupHeader()
    }

    private fun setupHolder() {
        binding.aboutSourceHolder.setOnClickListener { openUrl(REPO_URL) }
        binding.aboutReleasesHolder.setOnClickListener { openUrl(RELEASES_URL) }
        binding.aboutShareHolder.setOnClickListener { shareApp() }
        binding.aboutFaqHolder.setOnClickListener { showFaq() }
        binding.aboutIssuesHolder.setOnClickListener { openUrl(ISSUES_URL) }
        binding.aboutLibrariesHolder.setOnClickListener { showLibraries() }
        binding.aboutLicenseHolder.setOnClickListener { openUrl(LICENSE_URL) }
        binding.aboutVersionValue.text = "${BuildConfig.VERSION_NAME} \u00B7 ${BuildConfig.APPLICATION_ID}"
        binding.aboutVersionHolder.setOnClickListener { copyVersion() }

        updateTextColors(binding.aboutHolder)
        arrayOf(
            binding.aboutProjectLabel,
            binding.aboutSupportLabel,
            binding.aboutOtherLabel
        ).forEach { it.setTextColor(getProperPrimaryColor()) }

        binding.aboutHolder.applyExpressiveSettingsGroups()
        binding.aboutNestedScrollview.setBackgroundColor(pageColor())
    }

    private fun setupHeader() {
        val primary = getProperPrimaryColor()
        val text = getProperTextColor()
        val container = ColorUtils.blendARGB(pageColor(), primary, 0.16f)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        binding.aboutHeader.background = GradientDrawable().apply {
            setColor(container)
            cornerRadius = dp(28f).toFloat()
        }
        binding.aboutIcon.setImageDrawable(applicationInfo.loadIcon(packageManager))
        binding.aboutName.apply {
            this.text = getString(R.string.app_launcher_name)
            setTextColor(text)
            typeface = medium
        }
        binding.aboutAuthor.apply {
            setTextColor(ColorUtils.blendARGB(primary, text, 0.45f))
            typeface = medium
            setOnClickListener { openUrl(AUTHOR_URL) }
        }
        binding.aboutVersionChip.apply {
            this.text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(text)
            typeface = medium
            background = GradientDrawable().apply {
                setColor(ColorUtils.blendARGB(container, primary, 0.28f))
                cornerRadius = dp(100f).toFloat()
            }
        }
    }

    private fun pageColor(): Int {
        val bg = getProperBackgroundColor()
        return if (ColorUtils.calculateLuminance(bg) > 0.5) {
            if (isDynamicTheme()) {
                resources.getColor(org.fossify.commons.R.color.you_dialog_background_color, theme)
            } else {
                ColorUtils.blendARGB(bg, getProperPrimaryColor(), 0.08f)
            }
        } else {
            bg
        }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private fun selectableBackground(): Drawable? {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val drawable = attrs.getDrawable(0)
        attrs.recycle()
        return drawable
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.gallery_about_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareApp() {
        val name = getString(R.string.app_launcher_name)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, "$name\n$REPO_URL")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.gallery_about_share)))
    }

    private fun copyVersion() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = "${getString(R.string.app_launcher_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.gallery_about_version), value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.gallery_about_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun faqEntries(): List<Pair<String, String>> {
        val restricted = isRPlus() && !isExternalStorageManager()
        val entries = ArrayList<Pair<String, String>>()

        if (restricted) {
            entries += getString(R.string.faq_16_title) to "${getString(R.string.faq_16_text)} ${getString(R.string.faq_16_text_extra)}"
            entries += getString(R.string.faq_17_title) to getString(R.string.faq_17_text)
        }

        entries += getString(R.string.faq_3_title) to getString(R.string.faq_3_text)
        entries += getString(R.string.faq_12_title) to getString(R.string.faq_12_text)
        if (!restricted) {
            entries += getString(R.string.faq_7_title) to getString(R.string.faq_7_text)
            entries += getString(R.string.faq_14_title) to getString(R.string.faq_14_text)
        }
        entries += getString(R.string.faq_1_title) to getString(R.string.faq_1_text)
        entries += getString(R.string.faq_5_title) to getString(R.string.faq_5_text)
        entries += getString(R.string.faq_4_title) to getString(R.string.faq_4_text)
        entries += getString(R.string.faq_6_title) to getString(R.string.faq_6_text)
        if (!restricted) {
            entries += getString(R.string.faq_8_title) to getString(R.string.faq_8_text)
        }
        entries += getString(R.string.faq_10_title) to getString(R.string.faq_10_text)
        entries += getString(R.string.faq_11_title) to getString(R.string.faq_11_text)
        entries += getString(R.string.faq_13_title) to getString(R.string.faq_13_text)
        entries += getString(R.string.faq_15_title) to getString(R.string.faq_15_text)
        entries += getString(R.string.faq_2_title) to getString(R.string.faq_2_text)
        entries += getString(R.string.faq_18_title) to getString(R.string.faq_18_text)
        return entries
    }

    private fun showFaq() {
        val textColor = getProperTextColor()
        showScrollDialog(R.string.gallery_about_faq) { content ->
            faqEntries().forEachIndexed { index, (question, answer) ->
                content.addView(
                    TextView(this).apply {
                        text = question
                        setTextColor(textColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = if (index == 0) 0 else dp(20f) }
                )
                content.addView(
                    TextView(this).apply {
                        text = answer.trim().replace(Regex("\\s*\\n\\s*"), "\n")
                        setTextColor(ColorUtils.setAlphaComponent(textColor, 200))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setLineSpacing(0f, 1.15f)
                    },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f) }
                )
            }
        }
    }

    private fun showLibraries() {
        val textColor = getProperTextColor()
        val accent = ColorUtils.blendARGB(getProperPrimaryColor(), textColor, 0.45f)
        showScrollDialog(R.string.gallery_about_libraries) { content ->
            content.addView(
                TextView(this).apply {
                    setText(R.string.gallery_about_libraries_intro)
                    setTextColor(ColorUtils.setAlphaComponent(textColor, 200))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(0f, 1.15f)
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8f) }
            )
            LIBRARIES.forEach { (name, url) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10f), 0, dp(10f))
                    background = selectableBackground()
                    setOnClickListener { openUrl(url) }
                }
                row.addView(TextView(this).apply {
                    text = name
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                row.addView(TextView(this).apply {
                    text = url.removePrefix("https://").trimEnd('/')
                    setTextColor(accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                content.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }

    private fun showScrollDialog(titleRes: Int, fill: (LinearLayout) -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
        }
        fill(content)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val builder = getAlertDialogBuilder().setPositiveButton(org.fossify.commons.R.string.ok, null)
        setupDialogStuff(scroll, builder, titleRes)
    }
}
