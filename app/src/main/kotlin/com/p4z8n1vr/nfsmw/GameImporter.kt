package com.p4z8n1vr.nfsmw

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.function.Consumer
import kotlin.math.roundToInt

class GameImporter(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cancelled = false

    // Use Android's built-in document picker.
    @Suppress("DEPRECATION")
    fun pick(): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        return try {
            activity.startActivityForResult(intent, REQUEST)
            true
        } catch (_: ActivityNotFoundException) {
            message(
                R.string.import_game,
                activity.getString(R.string.no_file_picker),
                null
            )
            false
        }
    }

    // Keep Java Consumer<Boolean> compatibility.
    // Callback is delivered on the main thread.
    fun copy(uri: Uri, dir: File, done: Consumer<Boolean>) {
        val name = displayName(uri)
        val size = size(uri)

        if (
            name == null ||
            !name.lowercase(Locale.ROOT).endsWith(".iso")
        ) {
            message(
                R.string.import_game,
                activity.getString(R.string.choose_iso)
            ) {
                done.accept(false)
            }
            return
        }

        dir.mkdirs()

        if (
            size > 0 &&
            dir.usableSpace < size + (64L shl 20)
        ) {
            message(
                R.string.not_enough_space,
                activity.getString(
                    R.string.space_needed,
                    size / 1e9,
                    dir.usableSpace / 1e9
                )
            ) {
                done.accept(false)
            }
            return
        }

        val bar = ProgressBar(
            activity,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            isIndeterminate = size <= 0
        }

        val text = TextView(activity).apply {
            setText(R.string.import_starting)
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            val pad = (
                20 * activity.resources.displayMetrics.density
            ).roundToInt()

            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(text)
        }

        cancelled = false

        val progress = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.importing, name))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelled = true
            }
            .show()

        progress.window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val part = File(dir, "$name.part")
        val iso = File(dir, name)

        Thread({
            var error: String? = null

            try {
                val descriptor = activity.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?: throw IOException(
                        "Cannot open selected ISO"
                    )

                descriptor.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        val src = input.channel

                        if (isXboxImage(src) == false) {
                            error = activity.getString(
                                R.string.not_xbox_image,
                                name
                            )
                        } else {
                            FileOutputStream(part).use { output ->
                                val dst = output.channel
                                val buffer = ByteBuffer.allocateDirect(
                                    BUFFER
                                )

                                var copied = 0L
                                var shown = 0L

                                while (
                                    !cancelled &&
                                    src.read(buffer) > 0
                                ) {
                                    buffer.flip()

                                    while (buffer.hasRemaining()) {
                                        copied += dst.write(buffer)
                                    }

                                    buffer.clear()

                                    if (
                                        copied - shown >=
                                        (16L shl 20)
                                    ) {
                                        shown = copied
                                        val current = copied

                                        main.post {
                                            show(
                                                bar,
                                                text,
                                                current,
                                                size
                                            )
                                        }
                                    }
                                }

                                output.fd.sync()

                                if (
                                    !cancelled &&
                                    size > 0 &&
                                    copied != size
                                ) {
                                    error = activity.getString(
                                        R.string.copy_stopped
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "import failed", e)

                error = activity.getString(
                    R.string.copy_failed,
                    e.message
                )
            }

            val ok = error == null &&
                !cancelled &&
                part.renameTo(iso)

            if (!ok) {
                part.delete()

                if (error == null && !cancelled) {
                    error = activity.getString(
                        R.string.not_in_place
                    )
                }
            }

            val why = error

            main.post {
                progress.dismiss()

                when {
                    ok -> {
                        Log.i(TAG, "imported $iso")

                        message(
                            R.string.game_imported,
                            activity.getString(
                                R.string.game_imported_text,
                                name
                            )
                        ) {
                            done.accept(true)
                        }
                    }

                    why != null -> {
                        message(
                            R.string.import_failed,
                            why
                        ) {
                            done.accept(false)
                        }
                    }

                    else -> {
                        done.accept(false)
                    }
                }
            }
        }, "GameImport").start()
    }

    private fun show(
        bar: ProgressBar,
        text: TextView,
        copied: Long,
        size: Long
    ) {
        if (size > 0) {
            bar.progress = (copied * 1000 / size).toInt()

            text.text = activity.getString(
                R.string.import_progress,
                copied / 1e9,
                size / 1e9
            )
        } else {
            text.text = String.format(
                "%.2f GB",
                copied / 1e9
            )
        }
    }

    // Null means the provider does not support seeking.
    private fun isXboxImage(source: FileChannel): Boolean? {
        val bytes = ByteBuffer.allocate(MAGIC.size)

        return try {
            for (partition in PARTITIONS) {
                bytes.clear()

                if (
                    source.read(
                        bytes,
                        partition + 32L * 2048L
                    ) == MAGIC.size &&
                    bytes.array().contentEquals(MAGIC)
                ) {
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Log.i(
                TAG,
                "image not checked, no seeking: $e"
            )
            null
        }
    }

    private fun displayName(uri: Uri): String? {
        try {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (
                    cursor.moveToFirst() &&
                    !cursor.isNull(0)
                ) {
                    return File(cursor.getString(0)).name
                }
            }
        } catch (_: Exception) {
            // Fall back to URI path.
        }

        return uri.lastPathSegment?.let {
            File(it).name
        }
    }

    private fun size(uri: Uri): Long {
        try {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (
                    cursor.moveToFirst() &&
                    !cursor.isNull(0)
                ) {
                    return cursor.getLong(0)
                }
            }
        } catch (_: Exception) {
            // Unknown size uses indeterminate progress.
        }

        return -1L
    }

    private fun message(
        title: Int,
        description: String,
        after: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(description)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                after?.invoke()
            }
            .show()
    }

    companion object {
        // Accessible as GameImporter.REQUEST from Java.
        const val REQUEST = 0x8A11

        private const val TAG = "MostWantedImport"

        private val MAGIC =
            "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.UTF_8)

        private val PARTITIONS = longArrayOf(
            0,
            0x18300000L,
            0xFD90000L,
            0x2080000L
        )

        private const val BUFFER = 4 shl 20
    }
}
