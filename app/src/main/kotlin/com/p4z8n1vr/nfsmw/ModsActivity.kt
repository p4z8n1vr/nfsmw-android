package com.p4z8n1vr.nfsmw

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale

/**
 * Mods Loader: installs local archives containing cars or texture packs.
 * The browser downloads files; this activity never fetches mods itself.
 */
class ModsActivity : Activity() {
    companion object {
        private const val TAG = "MostWantedMods"
        private const val SITE = "https://www.nfsaddons.com/downloads/nfsmw/cars/"
        private const val SITE2 = "https://nfsmods.xyz/"
        private const val PICK = 0x4D4F
        private const val MAX_FILE = 4L shl 30
        private const val LOADER_VERSION = "1.0.0"

        private const val BG = 0xFF111317.toInt()
        private const val SURFACE = 0xFF1A1D23.toInt()
        private const val LINE = 0xFF2A2E36.toInt()
        private const val TEXT = 0xFFEDEEF0.toInt()
        private const val MUTED = 0xFF8D939E.toInt()
        private const val ACCENT = 0xFFFF7A00.toInt()
        private const val ON_ACCENT = 0xFF111317.toInt()

        private const val PREFS = "mods"
        private const val LANG = "lang"
        private val LANGS = arrayOf(arrayOf("en", "EN"), arrayOf("in", "ID"))

        private fun language(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LANG, "en") ?: "en"

        private fun carKey(car: CarConverter.Kept): String = "car:${car.car}"
        private fun packKey(pack: TexturePacks.Kept): String = "pack:${pack.id}"
    }

    private lateinit var count: TextView
    private lateinit var headTitle: TextView
    private lateinit var headTools: LinearLayout
    private lateinit var carList: LinearLayout
    private var selecting = false
    private val selected: MutableSet<String> = LinkedHashSet()
    private var shownCars: List<CarConverter.Kept> = emptyList()
    private var shownPacks: List<TexturePacks.Kept> = emptyList()
    private var dialog: AlertDialog? = null
    @Volatile private var cancelled = false
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    @Volatile private var expected = -1L
    private var updateOffered = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val configuration = Configuration()
        val tag = language(base).let {
            if (it == "in") "id" else it
        }
        configuration.setLocale(Locale.forLanguageTag(tag))
        applyOverrideConfiguration(configuration)
    }

    private fun setLanguage(lang: String) {
        if (lang == language(this)) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(LANG, lang).commit()
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        buildUi()
        if (savedInstanceState == null) takeIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
        showInstalled()
    }

    private fun configureSystemBars() {
        val lightIcons =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

        window.insetsController?.setSystemBarsAppearance(
            0,
            lightIcons
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setLegacyBarColors()
        } else {
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Suppress("DEPRECATION")
    private fun setLegacyBarColors() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
    }

    private fun setContentWithInsets(content: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setContentView(content)
            return
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(BG)

            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout()
                )

                view.setPadding(
                    safe.left,
                    safe.top,
                    safe.right,
                    safe.bottom
                )

                insets
            }
        }

        setContentView(container)
        container.requestApplyInsets()
    }

    private fun dp(value: Float): Int =
        Math.round(value * resources.displayMetrics.density)

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun twoColumns(): Boolean {
        val config = resources.configuration
        return config.screenWidthDp >= 720 ||
            (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 560)
    }

    private fun buildUi() {
        val two = twoColumns()
        val edge = dp(if (two) 28 else 16)
        val main = column().apply {
            addView(header(), spaced(dp(24)))
            addView(action(R.drawable.mods_ic_open, R.string.mods_open_site,
                R.string.mods_open_site_sub) { openSite() }, spaced(dp(8)))
            addView(action(R.drawable.mods_ic_open, R.string.mods_open_site2,
                R.string.mods_open_site_sub) { openLink(SITE2) }, spaced(dp(8)))
            addView(action(R.drawable.mods_ic_file, R.string.mods_from_file,
                R.string.mods_from_file_sub) { pickFile() }, spaced(dp(16)))
            addView(playButton(), spaced(dp(28)))
            addView(steps(), spaced(dp(28)))
            addView(credits(), spaced(dp(28)))
            addView(versions())
        }

        carList = column()
        val carsHead = carsHeader()

        if (two) {
            val root = LinearLayout(this).apply { setBackgroundColor(BG) }
            val left = ScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                main.setPadding(edge, edge, edge, edge)
                addView(main)
            }
            root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val rule = View(this).apply { setBackgroundColor(LINE) }
            root.addView(rule, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            val right = column()
            carsHead.setPadding(edge, edge, edge, dp(8))
            right.addView(carsHead)
            val list = ScrollView(this)
            carList.setPadding(edge, 0, edge, edge)
            list.addView(carList)
            right.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentWithInsets(root)
        } else {
            val col = column().apply {
                setPadding(edge, dp(20), edge, dp(24))
                addView(main, spaced(dp(28)))
                addView(carsHead, spaced(dp(8)))
                addView(carList)
            }
            val scroll = ScrollView(this).apply {
                setBackgroundColor(BG)
                addView(col)
            }
            setContentWithInsets(scroll)
        }
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun spaced(bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = bottom }

    private fun shape(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pressable(bg: Drawable, radius: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, shape(Color.WHITE, radius))

    private fun text(value: CharSequence, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun icon(resource: Int, tint: Int, size: Int): ImageView = ImageView(this).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun header(): View {
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_mods)
            background = shape(Color.TRANSPARENT, 10)
            clipToOutline = true
        }
        box.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(14)
        })
        val words = column().apply {
            addView(text(getString(R.string.mods_title), 20, TEXT, true))
            addView(text(getString(R.string.mods_subtitle), 13, MUTED, false))
        }
        box.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(languageSwitch())
        return box
    }

    private fun languageSwitch(): View {
        val box = LinearLayout(this)
        val frame = shape(Color.TRANSPARENT, 8).apply { setStroke(dp(1), LINE) }
        box.background = frame
        box.setPadding(dp(3), dp(3), dp(3), dp(3))
        box.contentDescription = getString(R.string.mods_language)
        val now = language(this)
        for (entry in LANGS) {
            val on = entry[0] == now
            val item = text(entry[1], 13, if (on) ON_ACCENT else MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = if (on) shape(ACCENT, 6)
                    else pressable(shape(Color.TRANSPARENT, 6), 6)
                setOnClickListener { setLanguage(entry[0]) }
            }
            box.addView(item)
        }
        return box
    }

    private fun action(
        iconRes: Int,
        title: Int,
        subtitle: Int,
        click: View.OnClickListener
    ): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(shape(SURFACE, 8), 8)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            setOnClickListener(click)
        }
        val image = icon(iconRes, ACCENT, 24)
        (image.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16)
        row.addView(image)
        val words = column().apply {
            addView(text(getString(title), 16, TEXT, true))
            addView(text(getString(subtitle), 13, MUTED, false))
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(icon(R.drawable.mods_ic_chevron, MUTED, 22))
        return row
    }

    private fun playButton(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = pressable(shape(ACCENT, 8), 8)
        minimumHeight = dp(52)
        setOnClickListener { play() }
        addView(icon(R.drawable.mods_ic_play, ON_ACCENT, 24))
        addView(text(getString(R.string.mods_play), 16, ON_ACCENT, true).apply {
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun steps(): View = column().apply {
        addView(sectionTitle(R.string.mods_steps), spaced(dp(10)))
        val lines = getString(R.string.mods_how).split("\n")
        for ((index, line) in lines.withIndex()) {
            val row = LinearLayout(this@ModsActivity)
            val number = text("${index + 1}.", 14, TEXT, true).apply { minWidth = dp(22) }
            row.addView(number)
            row.addView(text(line, 14, MUTED, false),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row, spaced(dp(6)))
        }
    }

    private fun credits(): View = column().apply {
        addView(sectionTitle(R.string.credits), spaced(dp(10)))
        addView(text(getString(R.string.port_credit), 14, TEXT, false), spaced(dp(2)))
        addView(text(getString(R.string.mods_credit_cars), 13, MUTED, false), spaced(dp(12)))
        addView(LinearLayout(this@ModsActivity).apply {
            addView(link(R.string.donate, R.string.donate_url, true))
            addView(link(R.string.github, R.string.github_url, false))
            addView(link(R.string.youtube, R.string.youtube_url, false))
        })
    }

    private fun versions(): View = column().apply {
        addView(sectionTitle(R.string.mods_version), spaced(dp(10)))
        var version = "?"
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            version = "${info.versionName} (${info.longVersionCode})"
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            // This is our own package and should always be installed.
        }
        addView(versionRow("Most Wanted Android", version), spaced(dp(6)))
        addView(versionRow("Mods Loader", LOADER_VERSION), spaced(0))
    }

    private fun versionRow(name: String, version: String): View = LinearLayout(this).apply {
        addView(text(name, 14, MUTED, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(version, 14, TEXT, false))
    }

    private fun link(label: Int, url: Int, first: Boolean): View =
        text(getString(label), 14, if (first) ACCENT else TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val frame = shape(Color.TRANSPARENT, 8).apply {
                setStroke(dp(1), if (first) ACCENT else LINE)
            }
            background = pressable(frame, 8)
            setOnClickListener { openLink(getString(url)) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!first) marginStart = dp(8)
            }
        }

    private fun sectionTitle(resource: Int): TextView =
        text(getString(resource).uppercase(Locale.getDefault()), 12, MUTED, true).apply {
            letterSpacing = 0.08f
        }

    private fun carsHeader(): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
        }
        headTitle = sectionTitle(R.string.mods_manage)
        row.addView(headTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        count = text("0", 12, MUTED, true)
        row.addView(count)
        headTools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(headTools)
        return row
    }

    private fun chip(label: Int, strong: Boolean, click: View.OnClickListener): TextView =
        text(getString(label), 13, if (strong) ACCENT else TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val frame = shape(Color.TRANSPARENT, 8).apply {
                setStroke(dp(1), if (strong) ACCENT else LINE)
            }
            background = pressable(frame, 8)
            setOnClickListener(click)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }

    private fun carRow(car: CarConverter.Kept): View = modRow(
        car.title, car.car, carKey(car)
    ) { askRemove(getString(R.string.mods_remove_text, car.title)) { remove(car) } }

    private fun packRow(pack: TexturePacks.Kept): View {
        val subtitle = getString(R.string.mods_texture_pack, pack.count) +
            " · ${pack.size} px" + (if (pack.author.isEmpty()) "" else " · ${pack.author}")
        return modRow(pack.name, subtitle, packKey(pack)) {
            askRemove(getString(R.string.mods_remove_pack_text, pack.name)) { removePack(pack) }
        }
    }

    private fun toggle(key: String) {
        if (!selected.remove(key)) selected.add(key)
        showInstalled()
    }

    private fun modRow(title: String, subtitle: String, key: String, onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            if (selecting) {
                background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, shape(Color.WHITE, 0))
                setOnClickListener { toggle(key) }
            }
        }
        val words = column().apply {
            addView(text(title, 15, TEXT, false).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(text(subtitle, 12, MUTED, false).apply { isSingleLine = true })
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selecting) {
            val box = CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(ACCENT)
                isChecked = selected.contains(key)
                contentDescription = title
                setOnClickListener { toggle(key) }
            }
            row.addView(box)
            return row
        }
        val delete = icon(R.drawable.mods_ic_delete, MUTED, 40).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, null)
            contentDescription = getString(R.string.mods_remove) + " " + title
            setOnClickListener { onRemove() }
        }
        row.addView(delete)
        return row
    }

    private fun rule(): View = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    override fun onResume() {
        super.onResume()
        showInstalled()
        offerUpdate()
    }

    private fun offerUpdate() {
        if (updateOffered || !hasAccess() || !ModInstaller.outdated() || dialog?.isShowing == true) return
        updateOffered = true
        val message = StringBuilder(getString(R.string.mods_update_text))
        if (ModInstaller.gameRunning(this)) {
            message.append("\n\n").append(getString(R.string.mods_game_closes))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mods_update_title)
            .setMessage(message)
            .setPositiveButton(R.string.mods_update) { _, _ -> refresh() }
            .setNegativeButton(R.string.mods_later, null)
            .show()
    }

    private fun refresh() {
        dialog = progress(getString(R.string.mods_update_title), R.string.mods_converting, false)
        Thread({
            var error: String? = null
            try {
                ModInstaller.refresh(this) { line -> Log.i(TAG, line) }
            } catch (e: Exception) {
                Log.w(TAG, "refresh", e)
                error = getString(R.string.mods_error, e.message.toString())
            }
            val why = error
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog?.dismiss()
                showInstalled()
                if (why != null) tell(R.string.mods_failed, why)
            }
        }, "ModRefresh").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun showInstalled() {
        val cars: List<CarConverter.Kept> = if (hasAccess()) ModInstaller.kept() else emptyList()
        val packs: List<TexturePacks.Kept> = if (hasAccess()) TexturePacks.kept() else emptyList()
        shownCars = cars
        shownPacks = packs
        val total = cars.size + packs.size
        val there = HashSet<String>()
        for (car in cars) there.add(carKey(car))
        for (pack in packs) there.add(packKey(pack))
        selected.retainAll(there)
        if (total == 0) selecting = false

        count.text = total.toString()
        headTitle.text = (if (selecting) getString(R.string.mods_selected, selected.size)
            else getString(R.string.mods_manage)).uppercase(Locale.getDefault())
        count.visibility = if (selecting) View.GONE else View.VISIBLE
        headTools.removeAllViews()

        if (selecting) {
            headTools.addView(chip(R.string.mods_select_all, false) {
                selected.addAll(there)
                showInstalled()
            })
            headTools.addView(chip(android.R.string.cancel, false) {
                selecting = false
                selected.clear()
                showInstalled()
            })
        } else if (total > 0) {
            headTools.addView(chip(R.string.mods_select, false) {
                selecting = true
                showInstalled()
            })
            headTools.addView(chip(R.string.mods_remove_all, true) { askRemoveMany(cars, packs) })
        }

        carList.removeAllViews()
        if (selecting) carList.addView(removeSelectedButton(), spaced(dp(8)))
        for ((index, pack) in packs.withIndex()) {
            if (index > 0) carList.addView(rule())
            carList.addView(packRow(pack))
        }
        if (packs.isNotEmpty() && cars.isNotEmpty()) carList.addView(rule())
        if (cars.isEmpty() && packs.isEmpty()) {
            val none = text(getString(R.string.mods_none), 14, MUTED, false).apply {
                setPadding(0, dp(12), 0, dp(12))
            }
            carList.addView(none)
            return
        }
        for ((index, car) in cars.withIndex()) {
            if (index > 0) carList.addView(rule())
            carList.addView(carRow(car))
        }
    }

    private fun openSite() = openLink(SITE)

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            tell(R.string.mods_failed, getString(R.string.mods_no_browser))
        }
    }

    private fun takeIntent(incoming: Intent?) {
        if (incoming == null) return
        val uri: Uri? = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) installFrom(uri)
    }

    private fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun ready(): Boolean {
        if (!hasAccess()) {
            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.storage_access)
                .setMessage(R.string.mods_need_access)
                .setPositiveButton(R.string.mods_play) { _, _ -> play() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return false
        }
        if (ModInstaller.findGame(this) == null) {
            tell(R.string.mods_failed, getString(R.string.mods_no_game,
                "/storage/emulated/0/" + MostWantedActivity.SHARED))
            return false
        }
        return true
    }

    private fun progress(title: String, message: Int, canCancel: Boolean): AlertDialog {
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        progressText = TextView(this).apply { setText(message) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Math.round(20 * resources.displayMetrics.density)
            setPadding(pad, pad / 2, pad, 0)
            addView(progressBar)
            addView(progressText)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title).setView(box).setCancelable(false)
        if (canCancel) builder.setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
        val shown = builder.show()
        shown.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return shown
    }

    private fun pickFile() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, PICK)
        } catch (_: ActivityNotFoundException) {
            tell(R.string.mods_failed, getString(R.string.mods_no_picker))
        }
    }

    @Deprecated("Maintained for compatibility with existing document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (requestCode == PICK && resultCode == RESULT_OK) data?.data else null
        if (uri != null) installFrom(uri)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(1)) expected = cursor.getLong(1)
                    val name = cursor.getString(0)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "file name", e)
        }
        return uri.lastPathSegment?.let { File(it).name } ?: "?"
    }

    private fun installFrom(uri: Uri) {
        if (!ready()) return
        cancelled = false
        expected = -1L
        val name = displayName(uri)
        dialog = progress(name, R.string.mods_reading, true)
        Thread({
            val dir = File(cacheDir, "mods")
            dir.mkdirs()
            val file = File(dir, "download.bin")
            var error: String? = null
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("the file cannot be opened")
                input.use { save(it, file) }
            } catch (e: Exception) {
                Log.w(TAG, "read", e)
                error = getString(R.string.mods_error, e.message.toString())
            }
            val why = error
            val pack = why == null && !cancelled && ModArchive.isTexturePack(file)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog?.dismiss()
                if (why != null || cancelled) {
                    file.delete()
                    if (why != null) tell(R.string.mods_failed, why)
                    return@runOnUiThread
                }
                if (pack) chooseResolution(name, file) else confirm(name, file)
            }
        }, "ModRead").start()
    }

    private fun chooseResolution(name: String, file: File) {
        val sizes = TexturePacks.SIZES
        val notes = intArrayOf(
            R.string.mods_res_small, R.string.mods_res_recommended,
            R.string.mods_res_large, R.string.mods_res_huge
        )
        val labels = Array(sizes.size) { index ->
            "${sizes[index]} px — ${getString(notes[index])}"
        }
        var chosen = 1
        for (i in sizes.indices) if (sizes[i] == TexturePacks.max) chosen = i
        val pick = intArrayOf(chosen)
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mods_res_title)
            .setSingleChoiceItems(labels, chosen) { _, which -> pick[0] = which }
            .setCancelable(false)
            .setPositiveButton(R.string.mods_next) { _, _ ->
                TexturePacks.max = sizes[pick[0]]
                confirm(name, file)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> file.delete() }
            .show()
    }

    private fun confirm(name: String, file: File) {
        val message = StringBuilder(getString(R.string.mods_confirm, name))
        if (ModInstaller.gameRunning(this)) {
            message.append("\n\n").append(getString(R.string.mods_game_closes))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mods_install)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.mods_install) { _, _ -> install(name, file) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> file.delete() }
            .show()
    }

    private fun keepFailed(file: File, name: String) {
        val logs = File(File(Environment.getExternalStorageDirectory(), MostWantedActivity.SHARED), "logs")
        logs.listFiles { _, filename -> filename.startsWith("failed_") }
            ?.forEach { it.delete() }
        try {
            logs.mkdirs()
            Files.copy(file.toPath(), File(logs, "failed_" + File(name).name).toPath())
        } catch (e: IOException) {
            Log.w(TAG, "keep failed", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "keep failed", e)
        }
    }

    private fun install(name: String, file: File) {
        dialog = progress(name, R.string.mods_converting, false)
        Thread({
            var result: ModInstaller.Installed? = null
            var error: String? = null
            try {
                result = ModInstaller.install(this, file, null) { line ->
                    Log.i(TAG, line)
                    when {
                        line.startsWith("car ") || line.startsWith("pack ") ->
                            runOnUiThread { progressText.text = line.substring(line.indexOf(' ') + 1) }
                        line.startsWith("read ") ->
                            runOnUiThread { progressText.text =
                                getString(R.string.mods_reading_files, line.substring(5)) }
                    }
                }
            } catch (e: CarConverter.Failure) {
                Log.w(TAG, "convert", e)
                error = explain(e)
            } catch (e: ModArchive.Unsupported) {
                error = getString(R.string.mods_bad_archive, e.message)
            } catch (_: OutOfMemoryError) {
                error = getString(R.string.mods_too_big)
            } catch (e: Exception) {
                Log.w(TAG, "install", e)
                error = getString(R.string.mods_error, e.message.toString())
            } finally {
                if (result == null) keepFailed(file, name)
                file.delete()
            }
            val done = result
            val why = error
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog?.dismiss()
                showInstalled()
                if (done != null) tellDone(done)
                else if (why != null) tell(R.string.mods_failed, why)
            }
        }, "ModInstall").start()
    }

    private fun explain(error: CarConverter.Failure): String = when (error.reason) {
        CarConverter.Failure.Reason.NO_CAR -> getString(R.string.mods_no_car)
        CarConverter.Failure.Reason.NAME_TOO_LONG ->
            getString(R.string.mods_name_long, error.message)
        else -> getString(R.string.mods_unsupported, error.message)
    }

    private fun tellDone(result: ModInstaller.Installed) {
        val message = StringBuilder(
            if (result.done.size == 1) getString(R.string.mods_done_addon, result.done[0])
            else getString(R.string.mods_done_pack, result.done.size)
        )
        if (result.failed.isNotEmpty()) {
            message.append("\n\n").append(getString(
                R.string.mods_pack_failed, TextUtils.join(", ", result.failed)
            ))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mods_done).setMessage(message)
            .setPositiveButton(R.string.mods_play) { _, _ -> play() }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun tell(title: Int, message: String) {
        dialog = AlertDialog.Builder(this)
            .setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun askRemove(messageText: String, remove: () -> Unit) {
        val message = StringBuilder(messageText)
        if (ModInstaller.gameRunning(this)) {
            message.append("\n\n").append(getString(R.string.mods_game_closes))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mods_remove).setMessage(message)
            .setPositiveButton(R.string.mods_remove) { _, _ -> remove() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun remove(car: CarConverter.Kept) {
        dialog = progress(car.title, R.string.mods_converting, false)
        Thread({
            var error: String? = null
            try {
                ModInstaller.uninstall(this, car.car) { line -> Log.i(TAG, line) }
            } catch (e: Exception) {
                Log.w(TAG, "remove", e)
                error = getString(R.string.mods_error, e.message.toString())
            }
            val why = error
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog?.dismiss()
                showInstalled()
                if (why != null) tell(R.string.mods_failed, why)
            }
        }, "ModRemove").start()
    }

    private fun removeSelectedButton(): View {
        val n = selected.size
        val button = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            background = if (n > 0) pressable(shape(ACCENT, 8), 8) else shape(SURFACE, 8)
            val ink = if (n > 0) ON_ACCENT else MUTED
            addView(icon(R.drawable.mods_ic_delete, ink, 20))
            addView(text(getString(R.string.mods_remove_selected, n), 15, ink, true).apply {
                setPadding(dp(6), 0, 0, 0)
            })
        }
        if (n > 0) {
            button.setOnClickListener {
                val cars = shownCars.filter { selected.contains(carKey(it)) }
                val packs = shownPacks.filter { selected.contains(packKey(it)) }
                askRemoveMany(cars, packs)
            }
        }
        return button
    }

    private fun askRemoveMany(cars: List<CarConverter.Kept>, packs: List<TexturePacks.Kept>) {
        val n = cars.size + packs.size
        if (n == 0) return
        if (n == 1) {
            if (cars.isEmpty()) {
                askRemove(getString(R.string.mods_remove_pack_text, packs[0].name)) {
                    removeMany(cars, packs)
                }
            } else {
                askRemove(getString(R.string.mods_remove_text, cars[0].title)) {
                    removeMany(cars, packs)
                }
            }
            return
        }
        askRemove(getString(R.string.mods_remove_many_text, n)) { removeMany(cars, packs) }
    }

    private fun removeMany(cars: List<CarConverter.Kept>, packs: List<TexturePacks.Kept>) {
        dialog = progress(getString(R.string.mods_remove), R.string.mods_converting, false)
        Thread({
            var error: String? = null
            try {
                for (pack in packs) TexturePacks.uninstall(this, pack.id)
                if (cars.isNotEmpty()) {
                    val names = cars.map { it.car }
                    ModInstaller.uninstall(this, names) { line -> Log.i(TAG, line) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "remove", e)
                error = getString(R.string.mods_error, e.message.toString())
            }
            val why = error
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog?.dismiss()
                selecting = false
                selected.clear()
                showInstalled()
                if (why != null) tell(R.string.mods_failed, why)
            }
        }, "ModRemove").start()
    }

    private fun removePack(pack: TexturePacks.Kept) {
        dialog = progress(pack.name, R.string.mods_converting, false)
        Thread({
            TexturePacks.uninstall(this, pack.id)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog?.dismiss()
                showInstalled()
            }
        }, "ModRemove").start()
    }

    private fun play() {
        startActivity(Intent(this, MostWantedActivity::class.java))
    }

    @Throws(IOException::class)
    private fun save(input: InputStream, target: File) {
        val buffer = ByteArray(1 shl 16)
        var got = 0L
        var shown = 0L
        FileOutputStream(target).use { output ->
            while (!cancelled) {
                val read = input.read(buffer)
                if (read <= 0) break
                got += read
                if (got > MAX_FILE) throw IOException("over ${MAX_FILE shr 20} MB")
                output.write(buffer, 0, read)
                if (got - shown >= (1 shl 19)) {
                    shown = got
                    val progress = got
                    val all = expected
                    runOnUiThread {
                        if (all > 0) {
                            progressBar.isIndeterminate = false
                            progressBar.max = 1000
                            progressBar.progress = (progress * 1000 / all).toInt()
                            progressText.text = String.format(
                                Locale.ROOT, "%.1f / %.1f MB",
                                progress / 1048576.0, all / 1048576.0
                            )
                        } else {
                            progressText.text = String.format(
                                Locale.ROOT, "%.1f MB", progress / 1048576.0
                            )
                        }
                    }
                }
            }
        }
    }
}
