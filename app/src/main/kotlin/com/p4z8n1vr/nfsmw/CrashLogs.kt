package com.p4z8n1vr.nfsmw

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// Records previous crash/ANR reports asynchronously.
internal object CrashLogs {
    private const val TAG = "MostWantedCrash"
    private const val PREFS = "crash_logs"
    private const val LAST = "last"

    private val busy = AtomicBoolean(false)

    @JvmStatic
    fun save(context: Context, dir: File, fallback: File) {
        if (
            Build.VERSION.SDK_INT < 30 ||
            !busy.compareAndSet(false, true)
        ) return

        val app = context.applicationContext

        Thread({
            try {
                saveNow(app, dir, fallback)
            } catch (error: Throwable) {
                Log.w(TAG, "crash logs", error)
            } finally {
                busy.set(false)
            }
        }, "crash-logs").start()
    }

    private fun saveNow(
        context: Context,
        dir: File,
        fallback: File
    ) {
        if (Build.VERSION.SDK_INT < 30) return

        val manager = context.getSystemService(
            ActivityManager::class.java
        ) ?: return

        val preferences = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val last = preferences.getLong(LAST, 0L)
        var newest = last

        for (
            exit in manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                0
            )
        ) {
            val reason = exit.reason

            if (exit.timestamp <= last) continue

            if (
                reason != ApplicationExitInfo.REASON_CRASH_NATIVE &&
                reason != ApplicationExitInfo.REASON_CRASH &&
                reason != ApplicationExitInfo.REASON_ANR
            ) continue

            newest = maxOf(newest, exit.timestamp)

            val filename = "crash_${
                date("yyyy-MM-dd_HH-mm-ss", exit.timestamp)
            }.txt"

            val report = describe(exit)

            if (!write(File(dir, filename), report)) {
                write(File(fallback, filename), report)
            }
        }

        if (newest != last) {
            val editor = preferences.edit()
            editor.putLong(LAST, newest)
            editor.apply()
        }
    }

    private fun date(
        pattern: String,
        millis: Long
    ): String {
        return SimpleDateFormat(
            pattern,
            Locale.US
        ).format(Date(millis))
    }

    private fun write(
        file: File,
        data: ByteArray
    ): Boolean {
        return try {
            file.parentFile?.mkdirs()

            FileOutputStream(file).use {
                it.write(data)
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun describe(
        exit: ApplicationExitInfo
    ): ByteArray {
        val type = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "native crash"

            ApplicationExitInfo.REASON_ANR ->
                "not responding (ANR)"

            else -> "Java crash"
        }

        val text = StringBuilder()

        text.append("Most Wanted ")
            .append(type)
            .append(", ")
            .append(
                date(
                    "yyyy-MM-dd HH:mm:ss",
                    exit.timestamp
                )
            )
            .append('\n')

        text.append("Process: ")
            .append(exit.processName)
            .append(" (pid ")
            .append(exit.pid)
            .append(")\n")

        exit.description?.let {
            text.append("Description: ")
                .append(it)
                .append('\n')
        }

        text.append("Status: ")
            .append(exit.status)
            .append(", PSS ")
            .append(exit.pss / 1024)
            .append(" MB, RSS ")
            .append(exit.rss / 1024)
            .append(" MB\n\n")

        val trace = exit.traceInputStream?.use {
            it.readBytes()
        }

        when {
            trace == null -> {
                text.append(
                    "Android kept no trace of it.\n"
                )
            }

            exit.reason ==
                ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                try {
                    tombstone(
                        text,
                        Msg(trace, 0, trace.size)
                    )
                } catch (error: Exception) {
                    text.append(
                        "The tombstone could not be read ("
                    )
                        .append(error)
                        .append("), its bytes in hex:\n")

                    trace.forEachIndexed { index, value ->
                        text.append(
                            String.format(
                                Locale.US,
                                "%02x",
                                value.toInt() and 0xff
                            )
                        ).append(
                            if (index % 32 == 31) '\n'
                            else ' '
                        )
                    }

                    text.append('\n')
                }
            }

            else -> {
                text.append(
                    trace.toString(Charsets.UTF_8)
                )
            }
        }

        return text.toString().toByteArray(
            Charsets.UTF_8
        )
    }

    // Convert debuggerd tombstone protobuf to text.
    private fun tombstone(
        out: StringBuilder,
        entry: Msg
    ) {
        out.append(
            "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"
        )

        out.append("Build fingerprint: '")
            .append(entry.str(2))
            .append("'\n")

        out.append("Revision: '")
            .append(entry.str(3))
            .append("'\n")

        out.append("Timestamp: ")
            .append(entry.str(4))
            .append('\n')

        out.append("Process uptime: ")
            .append(entry.num(20))
            .append("s\n")

        val command = entry.strs(9).joinToString(" ")

        out.append("Cmdline: ")
            .append(command)
            .append('\n')

        val pid = entry.num(5)
        val tid = entry.num(6)

        val threads = linkedMapOf<Long, Msg>()

        for (item in entry.msgs(16)) {
            item.msg(2)?.let {
                threads[item.num(1)] = it
            }
        }

        val crashed = threads[tid]

        out.append("pid: ")
            .append(pid)
            .append(", tid: ")
            .append(tid)
            .append(", name: ")
            .append(crashed?.str(2) ?: "?")
            .append("  >>> ")
            .append(command)
            .append(" <<<\n")

        out.append("uid: ")
            .append(entry.num(7))
            .append('\n')

        entry.msg(10)?.let { sig ->
            out.append("signal ")
                .append(sig.num(1).toInt())
                .append(" (")
                .append(sig.str(2))
                .append("), code ")
                .append(sig.num(3).toInt())
                .append(" (")
                .append(sig.str(4))
                .append(')')

            if (sig.num(8) != 0L) {
                out.append(", fault addr 0x")
                    .append(
                        java.lang.Long.toHexString(
                            sig.num(9)
                        )
                    )
            }

            if (sig.num(5) != 0L) {
                out.append(", from pid ")
                    .append(sig.num(7).toInt())
                    .append(", uid ")
                    .append(sig.num(6).toInt())
            }

            out.append('\n')
        }

        if (entry.str(14).isNotEmpty()) {
            out.append("Abort message: '")
                .append(entry.str(14))
                .append("'\n")
        }

        for (cause in entry.msgs(15)) {
            out.append("Cause: ")
                .append(cause.str(1))
                .append('\n')
        }

        if (crashed != null) {
            thread(out, crashed, true)
        }

        for ((id, item) in threads) {
            if (id == tid) continue

            out.append(
                "\n--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---\n"
            )

            out.append("pid: ")
                .append(pid)
                .append(", tid: ")
                .append(id)
                .append(", name: ")
                .append(item.str(2))
                .append("  >>> ")
                .append(command)
                .append(" <<<\n")

            thread(out, item, false)
        }

        for (buffer in entry.msgs(18)) {
            out.append("\n--------- log ")
                .append(buffer.str(1))
                .append('\n')

            for (line in buffer.msgs(2)) {
                val priority = line.num(4).toInt()

                val letter = if (priority in 2..7) {
                    "VDIWEF"[priority - 2]
                } else {
                    '?'
                }

                out.append(line.str(1))
                    .append(' ')
                    .append(
                        String.format(
                            Locale.US,
                            "%5d %5d",
                            line.num(2),
                            line.num(3)
                        )
                    )
                    .append(' ')
                    .append(letter)
                    .append(' ')
                    .append(line.str(5))
                    .append(": ")
                    .append(line.str(6))
                    .append('\n')
            }
        }
    }

    private fun thread(
        out: StringBuilder,
        item: Msg,
        registers: Boolean
    ) {
        if (registers) {
            val regs = item.msgs(3)

            for (index in regs.indices) {
                val reg = regs[index]

                out.append(
                    if (index % 4 == 0) "    "
                    else "  "
                ).append(
                    String.format(
                        Locale.US,
                        "%-4s %016x",
                        reg.str(1),
                        reg.num(2)
                    )
                )

                if (
                    index % 4 == 3 ||
                    index == regs.lastIndex
                ) {
                    out.append('\n')
                }
            }
        }

        for (note in item.strs(7)) {
            out.append("  NOTE: ")
                .append(note)
                .append('\n')
        }

        val frames = item.msgs(4)

        if (frames.size == 1) {
            out.append("\nbacktrace:\n")
        } else {
            out.append("\n")
                .append(frames.size)
                .append(" total frames\nbacktrace:\n")
        }

        for ((index, frame) in frames.withIndex()) {
            out.append(
                String.format(
                    Locale.US,
                    "      #%02d pc %016x  %s",
                    index,
                    frame.num(1),
                    frame.str(6)
                )
            )

            if (frame.num(7) != 0L) {
                out.append(" (offset 0x")
                    .append(
                        java.lang.Long.toHexString(
                            frame.num(7)
                        )
                    )
                    .append(')')
            }

            if (frame.str(4).isNotEmpty()) {
                out.append(" (")
                    .append(frame.str(4))
                    .append('+')
                    .append(frame.num(5))
                    .append(')')
            }

            if (frame.str(8).isNotEmpty()) {
                out.append(" (BuildId: ")
                    .append(frame.str(8))
                    .append(')')
            }

            out.append('\n')
        }
    }

    // Minimal protobuf reader for debuggerd tombstones.
    private class Msg(
        private val data: ByteArray,
        start: Int,
        private val end: Int
    ) {
        private var position = start

        private val fields =
            mutableMapOf<Int, MutableList<Any>>()

        init {
            require(
                start >= 0 &&
                end <= data.size &&
                start <= end
            )

            while (position < end) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val wire = (key and 7L).toInt()

                require(field > 0) {
                    "invalid field number"
                }

                val value: Any = when (wire) {
                    0 -> varint()
                    1 -> fixed(8)

                    2 -> {
                        val length = varint()

                        require(
                            length >= 0 &&
                            length <= end - position
                        ) {
                            "field $field runs over"
                        }

                        Range(
                            data,
                            position,
                            length.toInt()
                        ).also {
                            position += length.toInt()
                        }
                    }

                    5 -> fixed(4)
                    else -> error("wire type $wire")
                }

                fields.getOrPut(field) {
                    mutableListOf()
                }.add(value)
            }

            require(position == end) {
                "message runs over"
            }
        }

        private fun varint(): Long {
            var result = 0L

            for (shift in 0..63 step 7) {
                require(position < end) {
                    "truncated varint"
                }

                val byte =
                    data[position++].toInt() and 0xff

                result = result or (
                    (byte and 0x7f).toLong() shl shift
                )

                if ((byte and 0x80) == 0) {
                    return result
                }
            }

            error("overlong varint")
        }

        private fun fixed(bytes: Int): Long {
            require(position + bytes <= end) {
                "truncated fixed field"
            }

            var result = 0L

            for (index in bytes - 1 downTo 0) {
                result = (result shl 8) or (
                    data[position + index]
                        .toLong() and 0xffL
                )
            }

            position += bytes
            return result
        }

        fun num(id: Int): Long {
            return fields[id]?.firstOrNull()
                as? Long ?: 0L
        }

        fun str(id: Int): String {
            return (
                fields[id]?.firstOrNull()
                    as? Range
            )?.str() ?: ""
        }

        fun strs(id: Int): List<String> {
            return fields[id].orEmpty().mapNotNull {
                (it as? Range)?.str()
            }
        }

        fun msg(id: Int): Msg? {
            return (
                fields[id]?.firstOrNull()
                    as? Range
            )?.msg()
        }

        fun msgs(id: Int): List<Msg> {
            return fields[id].orEmpty().mapNotNull {
                (it as? Range)?.msg()
            }
        }
    }

    private class Range(
        private val data: ByteArray,
        private val start: Int,
        private val length: Int
    ) {
        fun str(): String {
            return String(
                data,
                start,
                length,
                Charsets.UTF_8
            )
        }

        fun msg(): Msg {
            return Msg(
                data,
                start,
                start + length
            )
        }
    }
}
