package com.p4z8n1vr.nfsmw

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.function.Consumer
import java.util.zip.ZipFile

// Extracts supported car and texture files into numbered cache files.
internal object ModArchive {

    class Unsupported(format: String) : IOException(format)

    private const val MAX_FILE = 268_435_456
    private const val MAX_ALL = 8_589_934_592L
    private const val ROOM = 268_435_456L
    private const val SEVEN_Z_MEMORY_KB = 262_144
    private const val BLOCK_SIZE = 65_536

    private var progress: Consumer<String> = Consumer { }

    @JvmStatic
    @Throws(IOException::class)
    fun read(
        file: File,
        spool: File
    ): Map<String, File> {
        return read(file, spool, Consumer { })
    }

    @JvmStatic
    @Throws(IOException::class)
    fun read(
        file: File,
        spool: File,
        log: Consumer<String>
    ): Map<String, File> {
        progress = log

        clear(spool)

        if (!spool.isDirectory && !spool.mkdirs()) {
            throw IOException("cannot write to $spool")
        }

        val magic = ByteArray(8)

        val got = FileInputStream(file).use {
            it.read(magic)
        }

        return when {
            got >= 4 &&
                magic[0] == 'P'.code.toByte() &&
                magic[1] == 'K'.code.toByte() -> {
                zip(file, spool)
            }

            got >= 6 &&
                magic[0] == '7'.code.toByte() &&
                magic[1] == 'z'.code.toByte() &&
                (magic[2].toInt() and 255) == 0xBC &&
                (magic[3].toInt() and 255) == 0xAF -> {
                sevenZ(file, spool)
            }

            got >= 7 &&
                magic[0] == 'R'.code.toByte() &&
                magic[1] == 'a'.code.toByte() &&
                magic[2] == 'r'.code.toByte() &&
                magic[3] == '!'.code.toByte() -> {
                libarchive(file, spool)
            }

            else -> {
                throw Unsupported(
                    if (
                        got > 0 &&
                        magic[0] == '<'.code.toByte()
                    ) {
                        "HTML"
                    } else {
                        "?"
                    }
                )
            }
        }
    }

    @JvmStatic
    fun isTexturePack(file: File): Boolean {
        try {
            for (path in names(file)) {
                val normalized = path.replace('\\', '/')

                if (
                    TexturePacks.packPaths(
                        listOf(normalized)
                    ).isNotEmpty()
                ) {
                    return true
                }
            }
        } catch (_: IOException) {
            // Diagnose the archive when it is installed.
        } catch (_: RuntimeException) {
            // Diagnose the archive when it is installed.
        } catch (_: LinkageError) {
            // Native archive library unavailable.
        }

        return false
    }

    @Throws(IOException::class)
    private fun names(file: File): List<String> {
        val out = mutableListOf<String>()
        val magic = ByteArray(8)

        val got = FileInputStream(file).use {
            it.read(magic)
        }

        if (
            got >= 4 &&
            magic[0] == 'P'.code.toByte() &&
            magic[1] == 'K'.code.toByte()
        ) {
            ZipFile(file).use { zip ->
                val entries = zip.entries()

                while (entries.hasMoreElements()) {
                    out.add(entries.nextElement().name)
                }
            }

            return out
        }

        if (
            got >= 6 &&
            magic[0] == '7'.code.toByte() &&
            magic[1] == 'z'.code.toByte()
        ) {
            try {
                SevenZFile.builder()
                    .setFile(file)
                    .setMaxMemoryLimitKiB(SEVEN_Z_MEMORY_KB)
                    .get().use { seven ->
                    for (entry in seven.entries) {
                        out.add(entry.name)
                    }
                }

                return out
            } catch (_: IOException) {
                // Fall back to libarchive.
            }
        }

        if (got < 4) return out

        val handle = Archive.readNew()

        try {
            Archive.readSupportFilterAll(handle)
            Archive.readSupportFormatAll(handle)

            Archive.readOpenFileName(
                handle,
                file.path.toByteArray(Charsets.UTF_8),
                1 shl 16
            )

            while (true) {
                val entry = Archive.readNextHeader(handle)

                if (entry == 0L) break

                val raw = ArchiveEntry.pathname(entry)

                val path = if (raw == null) {
                    ArchiveEntry.pathnameUtf8(entry)
                } else {
                    String(raw, Charsets.UTF_8)
                }

                if (path != null) {
                    out.add(path)
                }

                Archive.readDataSkip(handle)
            }
        } finally {
            Archive.readFree(handle)
        }

        return out
    }

    @JvmStatic
    fun clear(spool: File) {
        spool.listFiles()?.forEach {
            it.delete()
        }

        spool.delete()
    }

    private fun wanted(path: String): Boolean {
        return CarConverter.wanted(path) ||
            TexturePacks.wanted(path)
    }

    @Throws(IOException::class)
    private fun save(
        out: MutableMap<String, File>,
        spool: File,
        path: String,
        data: ByteArray,
        total: LongArray
    ) {
        var bytes = data

        if (
            TexturePacks.isImage(path) &&
            !CarConverter.wanted(path)
        ) {
            bytes = TexturePacks.shrink(bytes) ?: return
        }

        total[0] += bytes.size.toLong()

        if (total[0] > MAX_ALL) {
            throw IOException(
                "the archive's files are too large"
            )
        }

        if (
            spool.usableSpace <
            bytes.size.toLong() + ROOM
        ) {
            throw IOException(
                "not enough storage left on the phone"
            )
        }

        if (out.size % 20 == 19) {
            progress.accept("read ${out.size + 1}")
        }

        val dest = File(
            spool,
            out.size.toString()
        )

        FileOutputStream(dest).use {
            it.write(bytes)
        }

        out[path.replace('\\', '/')] = dest
    }

    private fun interface Chunk {
        @Throws(IOException::class)
        fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int
    }

    @Throws(IOException::class)
    private fun all(
        chunk: Chunk,
        size: Long
    ): ByteArray {
        if (size > MAX_FILE) {
            throw IOException(
                "a file in the archive is too large"
            )
        }

        var bytes = ByteArray(
            if (size > 0) size.toInt()
            else BLOCK_SIZE
        )

        var used = 0

        while (true) {
            if (used == bytes.size) {
                if (used >= MAX_FILE) {
                    throw IOException(
                        "a file in the archive is too large"
                    )
                }

                bytes = bytes.copyOf(
                    minOf(MAX_FILE, used * 2)
                )
            }

            val count = chunk.read(
                bytes,
                used,
                bytes.size - used
            )

            if (count < 0) break

            used += count
        }

        return if (used == bytes.size) {
            bytes
        } else {
            bytes.copyOf(used)
        }
    }

    @Throws(IOException::class)
    private fun zip(
        file: File,
        spool: File
    ): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val total = longArrayOf(0)

        ZipFile(file).use { zip ->
            val entries = zip.entries()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()

                if (
                    entry.isDirectory ||
                    !wanted(entry.name)
                ) continue

                zip.getInputStream(entry).use { input ->
                    save(
                        out,
                        spool,
                        entry.name,
                        all(
                            Chunk { buffer, offset, length ->
                                input.read(
                                    buffer,
                                    offset,
                                    length
                                )
                            },
                            entry.size
                        ),
                        total
                    )
                }
            }
        }

        return out
    }

    @Throws(IOException::class)
    private fun sevenZ(
        file: File,
        spool: File
    ): Map<String, File> {
        return try {
            sevenZJava(file, spool)
        } catch (_: IOException) {
            libarchive(file, spool)
        }
    }

    @Throws(IOException::class)
    private fun sevenZJava(
        file: File,
        spool: File
    ): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val total = longArrayOf(0)

        SevenZFile.builder()
            .setFile(file)
            .setMaxMemoryLimitKiB(SEVEN_Z_MEMORY_KB)
            .get().use { seven ->
            while (true) {
                val entry = seven.nextEntry ?: break

                if (
                    entry.isDirectory ||
                    !wanted(entry.name)
                ) continue

                save(
                    out,
                    spool,
                    entry.name,
                    all(
                        Chunk { buffer, offset, length ->
                            seven.read(
                                buffer,
                                offset,
                                length
                            )
                        },
                        entry.size
                    ),
                    total
                )
            }
        }

        return out
    }

    @Throws(IOException::class)
    private fun libarchive(
        file: File,
        spool: File
    ): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val total = longArrayOf(0)

        val handle = try {
            Archive.readNew()
        } catch (_: LinkageError) {
            throw Unsupported("libarchive")
        }

        try {
            Archive.readSupportFilterAll(handle)
            Archive.readSupportFormatAll(handle)

            Archive.readOpenFileName(
                handle,
                file.path.toByteArray(Charsets.UTF_8),
                1 shl 16
            )

            val buffer = ByteBuffer.allocateDirect(
                1 shl 16
            )

            while (true) {
                val entry = Archive.readNextHeader(handle)

                if (entry == 0L) break

                val raw = ArchiveEntry.pathname(entry)

                val path = if (raw == null) {
                    ArchiveEntry.pathnameUtf8(entry)
                } else {
                    String(raw, Charsets.UTF_8)
                }

                if (
                    path == null ||
                    (
                        ArchiveEntry.filetype(entry).toLong() and
                        ArchiveEntry.AE_IFMT.toLong()
                    ) != ArchiveEntry.AE_IFREG.toLong() ||
                    !wanted(path)
                ) continue

                val size = if (
                    ArchiveEntry.sizeIsSet(entry)
                ) {
                    ArchiveEntry.size(entry)
                } else {
                    0L
                }

                save(
                    out,
                    spool,
                    path,
                    all(
                        Chunk { bytes, offset, length ->
                            buffer.clear()

                            buffer.limit(
                                minOf(
                                    length,
                                    buffer.capacity()
                                )
                            )

                            Archive.readData(
                                handle,
                                buffer
                            )

                            buffer.flip()

                            val read = buffer.remaining()

                            if (read == 0) {
                                -1
                            } else {
                                buffer.get(
                                    bytes,
                                    offset,
                                    read
                                )

                                read
                            }
                        },
                        size
                    ),
                    total
                )
            }
        } finally {
            Archive.readFree(handle)
        }

        return out
    }
}
