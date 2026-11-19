package com.p4z8n1vr.nfsmw

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager

/**
 * SDL activity that starts the game, handles game files and storage access,
 * and supports Android controllers that SDL does not recognize directly.
 *
 * The class, JNI entry points, and game-facing methods retain their original
 * JVM names for the native runtime.
 */
class MostWantedActivity : SDLActivity() {
    companion object {
        const val SHARED = "MostWanted"

        private const val STORAGE_REQUEST = 0x426C
        private val ALL_FILES_ACCESS = Build.VERSION.SDK_INT >= 30

        // Keep these native methods on MostWantedActivity itself: native code
        // resolves their original static JNI names and signatures.
        @JvmStatic
        private external fun nativeLooseKey(keyCode: Int, down: Boolean)

        @JvmStatic
        private external fun nativeLooseAxes(
            lx: Float, ly: Float, rx: Float, ry: Float,
            lt: Float, rt: Float, hx: Float, hy: Float
        )

        @JvmStatic
        private external fun nativeLooseRelease()

        /** SD card or USB folders searched by the native game file loader. */
        @JvmStatic
        fun cardFolders(context: Context): List<File> {
            val result = ArrayList<File>()

            if (!ALL_FILES_ACCESS) {
                val directories = context.getExternalFilesDirs(null)
                for (i in 1 until directories.size) {
                    var root: File? = directories[i]
                    repeat(4) { root = root?.parentFile }
                    root?.let { result.add(File(it, SHARED)) }
                }
                return result
            }

            val manager = context.getSystemService(StorageManager::class.java)
                ?: return result
            for (volume in manager.storageVolumes) {
                val root = volume.directory
                if (volume.isPrimary || root == null ||
                    volume.state != Environment.MEDIA_MOUNTED
                ) continue
                result.add(File(root, SHARED))
            }
            return result
        }

        private fun hasGame(directory: File): Boolean {
            if (File(directory, "default.xbe").exists()) return true
            val files = directory.listFiles() ?: return false
            return files.any { it.name.lowercase(Locale.ROOT).endsWith(".iso") }
        }
    }

    private var dialog: AlertDialog? = null
    private var resumed = false
    private var update: UpdateChecker.Release? = null
    private val importer = GameImporter(this)
    private var importing = false
    private var checking = false
    private var storageAsked = false

    // SDL's orientation hint must not switch this landscape game to portrait.
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        if (w > 1 && h > 1) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun getLibraries(): Array<String> = arrayOf("SDL3", "main")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mSurface?.setOnCapturedPointerListener { view, event ->
            onCapturedPointer(view, event)
        }
        mLayout?.setOnCapturedPointerListener { view, event ->
            onCapturedPointer(view, event)
        }

        getExternalFilesDir(null)?.let { File(it, "assets").mkdirs() }

        UpdateChecker.check(this, false) { release, _ ->
            if (release != null) {
                update = release
                tellOfUpdate()
            }
        }
    }

    // Some vendor services expose a controller as a virtual Android device.
    // Forward only devices that SDL did not claim itself.
    private fun loose(event: InputEvent, sources: Int): Boolean =
        !SDLActivity.mBrokenLibraries &&
            (event.source and sources) != 0 &&
            !SDLControllerManager.isDeviceSDLJoystick(event.deviceId)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val pad = KeyEvent.isGamepadButton(code) ||
            code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT

        if (pad && loose(event, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) {
            if (event.repeatCount == 0) {
                nativeLooseKey(code, event.action == KeyEvent.ACTION_DOWN)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (loose(event, InputDevice.SOURCE_JOYSTICK) &&
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            nativeLooseAxes(
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
                maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_GAS)),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCapturedPointer(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                for (h in 0 until event.historySize) {
                    SDLActivity.onNativeMouse(
                        0, MotionEvent.ACTION_MOVE,
                        event.getHistoricalX(h), event.getHistoricalY(h), true
                    )
                }
                SDLActivity.onNativeMouse(
                    0, MotionEvent.ACTION_MOVE, event.x, event.y, true
                )
            }
            MotionEvent.ACTION_BUTTON_PRESS -> SDLActivity.onNativeMouse(
                event.buttonState, MotionEvent.ACTION_DOWN, 0f, 0f, true
            )
            MotionEvent.ACTION_BUTTON_RELEASE -> SDLActivity.onNativeMouse(
                event.buttonState, MotionEvent.ACTION_UP, 0f, 0f, true
            )
            MotionEvent.ACTION_SCROLL -> SDLActivity.onNativeMouse(
                0, MotionEvent.ACTION_SCROLL,
                event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL), false
            )
            // Native button events already represent pointer presses/releases.
        }
        return true
    }

    /** Called by the game thread when the player checks for updates. */
    fun checkForUpdates() {
        runOnUiThread {
            if (checking) return@runOnUiThread
            checking = true
            Toast.makeText(this, R.string.checking_updates, Toast.LENGTH_SHORT).show()

            UpdateChecker.check(this, true) { release, failed ->
                checking = false
                if (release != null) {
                    update = release
                    tellOfUpdate()
                } else if (!isFinishing && dialog?.isShowing != true) {
                    val message = if (failed) {
                        getString(R.string.update_unreachable)
                    } else {
                        getString(
                            R.string.latest_version,
                            UpdateChecker.installedVersion(this)
                        )
                    }
                    dialog = AlertDialog.Builder(this)
                        .setTitle(if (failed) R.string.update_check_failed else R.string.no_update)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /** Called from the game's options menu, on its own thread. */
    fun showCredits() {
        runOnUiThread {
            if (isFinishing || dialog?.isShowing == true) return@runOnUiThread

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }
            val creditText = TextView(this).apply {
                text = getString(R.string.port_credit)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val creditParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layout.addView(creditText, creditParams)

            fun addLink(label: Int, url: Int, bottomMargin: Int) {
                val button = Button(this).apply {
                    setText(label)
                    setOnClickListener { open(getString(url)) }
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, bottomMargin) }
                layout.addView(button, params)
            }

            addLink(R.string.donate, R.string.donate_url, 8)
            addLink(R.string.github, R.string.github_url, 8)
            addLink(R.string.youtube, R.string.youtube_url, 0)

            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.credits)
                .setView(layout)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    fun openDonation() {
        runOnUiThread {
            if (!isFinishing) open(getString(R.string.donate_url))
        }
    }

    /** Battery temperature in tenths of a degree Celsius, or -1. */
    fun batteryTemperature(): Int {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        askForGame()
        saveCrashLogs()
        tellOfUpdate()
    }

    private fun saveCrashLogs() {
        if (!hasStorageAccess()) return
        val fallback = File(getExternalFilesDir(null) ?: filesDir, "logs")
        val logs = File(File(Environment.getExternalStorageDirectory(), SHARED), "logs")
        CrashLogs.save(this, logs, fallback)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        else if (!SDLActivity.mBrokenLibraries) nativeLooseRelease()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT < 30) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            return
        }

        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hasStorageAccess(): Boolean {
        if (ALL_FILES_ACCESS) return Environment.isExternalStorageManager()
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun askForGame() {
        if (importing || dialog?.isShowing == true) return

        if (!hasStorageAccess()) {
            askAllFilesAccess()
            return
        }

        val own = getExternalFilesDir(null)
        if (own != null && hasGame(File(own, "assets"))) return

        val shared = File(Environment.getExternalStorageDirectory(), SHARED)
        shared.mkdirs()
        if (hasGame(shared) || hasGame(File(shared, "assets"))) return

        for (card in cardFolders()) {
            if (hasGame(card) || hasGame(File(card, "assets"))) return
        }
        tellWhereTheGameGoes()
    }

    private fun cardFolders(): List<File> = cardFolders(this)

    /** Called by the native game's file search. */
    fun cardFolderPaths(): Array<String> =
        cardFolders().map { it.path }.toTypedArray()

    private fun tellOfUpdate() {
        val release = update ?: return
        if (importing || !resumed || isFinishing || dialog?.isShowing == true) return
        update = null

        val installed = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }

        val message = StringBuilder(
            getString(R.string.update_is_available, release.name)
        )
        if (installed.isNotEmpty()) {
            message.append(' ')
                .append(getString(R.string.update_installed, installed))
        }
        message.append('.')

        if (release.changes.isNotEmpty()) {
            message.append("\n\n")
                .append(getString(R.string.update_whats_new))
                .append('\n')
                .append(release.changes)
        }
        message.append("\n\n").append(getString(R.string.update_install_over))

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_available)
            .setMessage(message)
            .setPositiveButton(R.string.download) { _, _ ->
                open(release.apk ?: release.page)
            }
            .setNeutralButton(R.string.skip_version) { _, _ ->
                UpdateChecker.skip(this, release.tag)
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser available.
        }
    }

    private fun askAllFilesAccess() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.storage_access)
            .setMessage(
                getString(
                    R.string.storage_text,
                    "/storage/emulated/0/$SHARED",
                    getString(
                        if (ALL_FILES_ACCESS) R.string.storage_all_files
                        else R.string.storage_permission
                    )
                )
            )
            .setCancelable(false)
            .setPositiveButton(R.string.allow_access) { _, _ ->
                openAccessSettings()
            }
            .setNeutralButton(R.string.import_game) { _, _ ->
                startImport()
            }
            .setOnDismissListener { tellOfUpdate() }
            .show()
    }

    private fun tellWhereTheGameGoes() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.game_files)
            .setMessage(
                getString(R.string.game_files_text, "/storage/emulated/0/$SHARED")
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.import_game) { _, _ -> startImport() }
            .setOnDismissListener { tellOfUpdate() }
            .show()
    }

    private fun startImport() {
        importing = importer.pick()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != GameImporter.REQUEST) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) {
            importing = false
            return
        }

        val directory = if (hasStorageAccess()) {
            File(Environment.getExternalStorageDirectory(), SHARED)
        } else {
            File(getExternalFilesDir(null), "assets")
        }
        importer.copy(uri, directory) { success ->
            importing = false
            if (!success) askForGame()
            tellOfUpdate()
        }
    }

    private fun openAccessSettings() {
        if (!ALL_FILES_ACCESS) {
            val ask = !storageAsked || shouldShowRequestPermissionRationale(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (ask) {
                storageAsked = true
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_REQUEST
                )
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }
}
