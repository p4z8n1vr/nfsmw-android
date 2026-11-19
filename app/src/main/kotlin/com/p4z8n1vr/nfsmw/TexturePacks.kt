package com.p4z8n1vr.nfsmw

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.function.Consumer

// NextGenGraphics texture packs: install .dds replacements and preserve game texture hashes.
internal object TexturePacks {
    @JvmField val SIZES = intArrayOf(512, 1024, 2048, 4096)
    @JvmField @Volatile var max = 1024

    private const val DIR = "textures"
    private const val INFO = "info.txt"
    private const val JSON = "texturepackinfo.json"
    private const val DXT1 = 0x31545844
    private const val DXT3 = 0x33545844
    private const val DXT5 = 0x35545844
    private const val RGBA = 0

    @JvmStatic fun dir(): File = File(ModInstaller.modsDir(), DIR)

    @JvmStatic fun wanted(path: String): Boolean {
        val name = path.lowercase(Locale.ROOT)
        return name.endsWith("/$JSON") || name == JSON || name.endsWith(".dds")
    }

    @JvmStatic fun isImage(path: String): Boolean =
        path.lowercase(Locale.ROOT).endsWith(".dds")

    @JvmStatic fun packPaths(paths: Iterable<String>): List<String> =
        paths.filter { path ->
            val name = path.lowercase(Locale.ROOT)
            name.endsWith("/$JSON") || name == JSON
        }

    class Kept {
        @JvmField var id: String = ""
        @JvmField var name: String = ""
        @JvmField var author: String = ""
        @JvmField var count: Int = 0
        @JvmField var size: Int = 0
    }

    @JvmStatic fun kept(): List<Kept> {
        val folders = dir().listFiles {
            item -> item.isDirectory && !item.name.startsWith(".")
        } ?: return emptyList()

        val result = ArrayList<Kept>()

        for (folder in folders.sortedBy { File(it, INFO).lastModified() }) {
            try {
                val lines = Files.readAllLines(
                    File(folder, INFO).toPath(),
                    Charsets.UTF_8
                )

                val entry = Kept()
                entry.id = folder.name
                entry.name = lines[0]
                entry.author = lines.getOrElse(1) { "" }
                entry.count = lines.getOrElse(2) { "0" }.trim().toInt()
                entry.size = lines.getOrElse(3) { "1024" }.trim().toInt()
                result.add(entry)
            } catch (_: IOException) {
                // Ignore incomplete packs.
            } catch (_: RuntimeException) {
                // Ignore invalid pack metadata.
            }
        }

        return result
    }

    @JvmStatic fun uninstall(context: Context, id: String) {
        ModInstaller.stopGame(context)
        deleteTree(File(dir(), id))
    }

    @JvmStatic
    @Throws(Exception::class)
    fun install(
        context: Context,
        files: Map<String, File>,
        log: Consumer<String>
    ): List<String> {
        val lower = HashMap<String, String>()

        for (path in files.keys) {
            lower[normal(path).lowercase(Locale.ROOT)] = path
        }

        val descriptions = packPaths(files.keys)
        val result = ArrayList<String>()
        val root = dir()

        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("cannot write to $root")
        }

        for ((number, json) in descriptions.withIndex()) {
            val source = files[json] ?: continue

            val data = JSONObject(
                lenient(
                    String(
                        Files.readAllBytes(source.toPath()),
                        Charsets.UTF_8
                    )
                )
            )

            val description = data.optJSONObject("description")
            val normalized = normal(json)
            val base = normalized.substringBeforeLast('/', "")
            val folder = base.substringAfterLast('/')

            var name = description?.optString("name", folder) ?: folder

            if (name.isEmpty()) {
                name = folder.ifEmpty { "Texture pack" }
            }

            val author = description?.optString("author", "") ?: ""
            val rootDir = data.optString("rootDirectory", "")
            val mappings = data.optJSONArray("textureMappings") ?: continue

            log.accept("pack ${number + 1}/${descriptions.size}: $name")

            val id = name.replace(
                Regex("[^A-Za-z0-9._ -]"), "_"
            ).trim()

            val work = File(root, ".new_$id")
            deleteTree(work)

            if (!work.mkdirs()) {
                throw IOException("cannot write to $work")
            }

            val made = HashSet<Int>()

            for (index in 0 until mappings.length()) {
                val mapping = mappings.optJSONObject(index) ?: continue
                val game = mapping.optString("gameId", "").trim()
                val path = mapping.optString("texturePath", "")

                if (game.isEmpty() || path.isEmpty()) continue

                val full = normal(join(base, join(rootDir, path)))
                val key = lower[full.lowercase(Locale.ROOT)] ?: continue

                val hash = try {
                    if (
                        game.regionMatches(
                            0, "0x", 0, 2, ignoreCase = true
                        )
                    ) {
                        java.lang.Long.parseLong(
                            game.substring(2), 16
                        ).toInt()
                    } else {
                        hash(game)
                    }
                } catch (_: NumberFormatException) {
                    continue
                }

                val image = File(
                    work,
                    String.format(Locale.ROOT, "%08x.dds", hash)
                )

                Files.copy(
                    files.getValue(key).toPath(),
                    image.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )

                made.add(hash)

                if (index % 50 == 0) {
                    log.accept(
                        "pack $name: $index/${mappings.length()}"
                    )
                }
            }

            if (made.isEmpty()) {
                deleteTree(work)
                continue
            }

            write(
                File(work, INFO),
                "$name\n$author\n${made.size}\n$max\n"
                    .toByteArray(Charsets.UTF_8)
            )

            ModInstaller.stopGame(context)

            val dest = File(root, id)
            deleteTree(dest)

            if (!work.renameTo(dest)) {
                throw IOException("cannot move $work to $dest")
            }

            result.add(name)
        }

        return result
    }

    @JvmStatic fun hash(name: String): Int {
        var result = -1

        for (
            byte in name.uppercase(Locale.ROOT)
                .toByteArray(Charsets.ISO_8859_1)
        ) {
            result = result * 33 + (byte.toInt() and 255)
        }

        return result
    }

    private fun lenient(text: String): String =
        text.removePrefix("\uFEFF")
            .replace(Regex(",\\s*([\\]}])"), "$1")

    private fun join(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a/$b"
    }

    private fun normal(path: String): String {
        val parts = ArrayList<String>()

        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) {
                    parts.removeAt(parts.lastIndex)
                } else {
                    Unit
                }
                else -> parts.add(segment)
            }
        }

        return parts.joinToString("/")
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    @Throws(IOException::class)
    private fun write(file: File, data: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(data)
            output.fd.sync()
        }
    }

    private fun i32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or
            ((bytes[offset + 1].toInt() and 255) shl 8) or
            ((bytes[offset + 2].toInt() and 255) shl 16) or
            ((bytes[offset + 3].toInt() and 255) shl 24)

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun levelSize(
        format: Int,
        width: Int,
        height: Int
    ): Int =
        if (format == RGBA) {
            width * height * 4
        } else {
            maxOf(1, (width + 3) / 4) *
                maxOf(1, (height + 3) / 4) *
                if (format == DXT1) 8 else 16
        }

    // Keep original mip selection and DXT/RGBA codec behavior.
    @JvmStatic fun shrink(data: ByteArray): ByteArray? {
        if (
            data.size < 128 ||
            i32(data, 0) != 0x20534444
        ) return null

        val height = i32(data, 12)
        val width = i32(data, 16)
        val flags = i32(data, 8)

        val mips = if ((flags and 0x20000) != 0) {
            maxOf(1, i32(data, 28))
        } else {
            1
        }

        val pf = i32(data, 80)
        val fourcc = i32(data, 84)
        val bits = i32(data, 88)

        if (
            width <= 0 || height <= 0 ||
            width > 16384 || height > 16384 ||
            (i32(data, 112) and 0x200) != 0 ||
            (i32(data, 112) and 0x200000) != 0
        ) return null

        val format = when {
            (pf and 4) != 0 -> {
                if (
                    fourcc != DXT1 &&
                    fourcc != DXT3 &&
                    fourcc != DXT5
                ) return null

                fourcc
            }

            (pf and 0x40) != 0 && bits == 32 -> RGBA
            else -> return null
        }

        val offsets = IntArray(mips)

        var have = 0
        var at = 128
        var w = width
        var h = height

        for (level in 0 until mips) {
            val length = levelSize(format, w, h)

            if (at + length > data.size) break

            offsets[have++] = at
            at += length
            w = maxOf(1, w / 2)
            h = maxOf(1, h / 2)
        }

        if (have == 0) return null

        var start = 0
        w = width
        h = height

        while (start + 1 < have && maxOf(w, h) > max) {
            start++
            w = maxOf(1, w / 2)
            h = maxOf(1, h / 2)
        }

        if (maxOf(w, h) <= max && have - start > 1) {
            if (start == 0 && have == mips) return data

            val length = at - offsets[start]
            val out = ByteArray(128 + length)

            System.arraycopy(data, 0, out, 0, 128)
            System.arraycopy(
                data,
                offsets[start],
                out,
                128,
                length
            )

            put32(out, 8, i32(data, 8) or 0x20000)
            put32(out, 12, h)
            put32(out, 16, w)
            put32(out, 28, have - start)
            put32(out, 20, levelSize(format, w, h))

            return out
        }

        val pixels = decodeShrunk(
            data,
            offsets[start],
            format,
            w,
            h,
            (pf and 1) != 0,
            i32(data, 92) == 0xFF
        )

        var factor = 1

        while (maxOf(w, h) / factor > max) {
            factor = factor shl 1
        }

        val outWidth = maxOf(1, w / factor)
        val outHeight = maxOf(1, h / factor)

        var first = if (factor == 1) {
            data.copyOfRange(
                offsets[start],
                offsets[start] + levelSize(format, w, h)
            )
        } else {
            null
        }

        if (first != null && format == RGBA) {
            first = rgba(pixels)
        }

        return encode(
            pixels,
            outWidth,
            outHeight,
            format,
            first
        )
    }

    private fun decodeShrunk(
        data: ByteArray,
        start: Int,
        format: Int,
        width: Int,
        height: Int,
        alpha: Boolean,
        rgbaOrder: Boolean
    ): IntArray {
        var factor = 1

        while (maxOf(width, height) / factor > max) {
            factor = factor shl 1
        }

        val outWidth = maxOf(1, width / factor)
        val outHeight = maxOf(1, height / factor)
        val out = IntArray(outWidth * outHeight)
        val accum = LongArray(outWidth * 4)
        val rows = IntArray(width * 4)
        val block = IntArray(16)

        val blocksWide = maxOf(1, (width + 3) / 4)
        val blocksHigh = maxOf(1, (height + 3) / 4)
        val bytesPerBlock = if (format == DXT1) 8 else 16

        var counted = 0

        for (
            by in 0 until if (format == RGBA) {
                (height + 3) / 4
            } else {
                blocksHigh
            }
        ) {
            if (format == RGBA) {
                for (y in 0 until 4) {
                    if (by * 4 + y >= height) break

                    for (x in 0 until width) {
                        val offset = start +
                            ((by * 4 + y) * width + x) * 4

                        val b0 = data[offset].toInt() and 255
                        val b1 = data[offset + 1].toInt() and 255
                        val b2 = data[offset + 2].toInt() and 255

                        val b3 = if (alpha) {
                            data[offset + 3].toInt() and 255
                        } else {
                            255
                        }

                        rows[y * width + x] = if (rgbaOrder) {
                            (b3 shl 24) or
                                (b0 shl 16) or
                                (b1 shl 8) or b2
                        } else {
                            (b3 shl 24) or
                                (b2 shl 16) or
                                (b1 shl 8) or b0
                        }
                    }
                }
            } else {
                for (bx in 0 until blocksWide) {
                    decodeBlock(
                        data,
                        start +
                            (by * blocksWide + bx) *
                            bytesPerBlock,
                        format,
                        block
                    )

                    for (y in 0 until 4) {
                        for (x in 0 until 4) {
                            if (bx * 4 + x >= width) break

                            rows[y * width + bx * 4 + x] =
                                block[y * 4 + x]
                        }
                    }
                }
            }

            for (y in 0 until 4) {
                val sourceY = by * 4 + y

                if (sourceY >= height) break

                val outY = sourceY / factor

                if (outY >= outHeight) break

                for (x in 0 until width) {
                    val outX = x / factor

                    if (outX >= outWidth) break

                    val color = rows[y * width + x]

                    accum[outX * 4] +=
                        (color ushr 24).toLong()

                    accum[outX * 4 + 1] +=
                        ((color ushr 16) and 255).toLong()

                    accum[outX * 4 + 2] +=
                        ((color ushr 8) and 255).toLong()

                    accum[outX * 4 + 3] +=
                        (color and 255).toLong()
                }

                counted++

                if (
                    counted == factor ||
                    sourceY == height - 1
                ) {
                    val count = counted.toLong() * factor

                    for (outX in 0 until outWidth) {
                        val a =
                            (accum[outX * 4] / count).toInt()
                        val r =
                            (accum[outX * 4 + 1] / count).toInt()
                        val g =
                            (accum[outX * 4 + 2] / count).toInt()
                        val b =
                            (accum[outX * 4 + 3] / count).toInt()

                        out[outY * outWidth + outX] =
                            (a shl 24) or
                            (r shl 16) or
                            (g shl 8) or b
                    }

                    accum.fill(0L)
                    counted = 0
                }
            }
        }

        return out
    }

    private fun expand565(color: Int): Int {
        val r = (color ushr 11) and 31
        val g = (color ushr 5) and 63
        val b = color and 31

        return (
            ((r shl 3) or (r ushr 2)) shl 16
        ) or (
            ((g shl 2) or (g ushr 4)) shl 8
        ) or (
            (b shl 3) or (b ushr 2)
        )
    }

    private fun decodeBlock(
        data: ByteArray,
        offset: Int,
        format: Int,
        out: IntArray
    ) {
        val colorOffset = if (format == DXT1) {
            offset
        } else {
            offset + 8
        }

        val c0 =
            (data[colorOffset].toInt() and 255) or
            ((data[colorOffset + 1].toInt() and 255) shl 8)

        val c1 =
            (data[colorOffset + 2].toInt() and 255) or
            ((data[colorOffset + 3].toInt() and 255) shl 8)

        val palette = IntArray(4)
        val e0 = expand565(c0)
        val e1 = expand565(c1)

        palette[0] = 0xFF000000.toInt() or e0
        palette[1] = 0xFF000000.toInt() or e1

        if (c0 > c1 || format != DXT1) {
            palette[2] =
                0xFF000000.toInt() or
                mix(e0, e1, 2, 1, 3)

            palette[3] =
                0xFF000000.toInt() or
                mix(e0, e1, 1, 2, 3)
        } else {
            palette[2] =
                0xFF000000.toInt() or
                mix(e0, e1, 1, 1, 2)

            palette[3] = 0
        }

        val bits = i32(data, colorOffset + 4)

        for (i in 0 until 16) {
            out[i] = palette[(bits ushr (i * 2)) and 3]
        }

        if (format == DXT3) {
            for (i in 0 until 16) {
                val a = (
                    (data[offset + i / 2].toInt() and 255)
                        ushr ((i and 1) * 4)
                    ) and 15

                out[i] =
                    (out[i] and 0xFFFFFF) or
                    ((a * 17) shl 24)
            }
        } else if (format == DXT5) {
            val a0 = data[offset].toInt() and 255
            val a1 = data[offset + 1].toInt() and 255
            val alpha = alphaPalette(a0, a1)

            var alphaBits = 0L

            for (i in 0 until 6) {
                alphaBits = alphaBits or (
                    (data[offset + 2 + i].toLong() and 255L)
                        shl (8 * i)
                )
            }

            for (i in 0 until 16) {
                out[i] = (out[i] and 0xFFFFFF) or (
                    alpha[
                        (
                            (alphaBits ushr (3 * i)) and 7L
                        ).toInt()
                    ] shl 24
                )
            }
        }
    }

    private fun alphaPalette(a0: Int, a1: Int): IntArray {
        val alpha = IntArray(8)
        alpha[0] = a0
        alpha[1] = a1

        if (a0 > a1) {
            for (i in 1 until 7) {
                alpha[i + 1] =
                    ((7 - i) * a0 + i * a1) / 7
            }
        } else {
            for (i in 1 until 5) {
                alpha[i + 1] =
                    ((5 - i) * a0 + i * a1) / 5
            }

            alpha[6] = 0
            alpha[7] = 255
        }

        return alpha
    }

    private fun mix(
        x: Int,
        y: Int,
        wx: Int,
        wy: Int,
        div: Int
    ): Int {
        val r = (
            ((x ushr 16) and 255) * wx +
            ((y ushr 16) and 255) * wy
        ) / div

        val g = (
            ((x ushr 8) and 255) * wx +
            ((y ushr 8) and 255) * wy
        ) / div

        val b = (
            (x and 255) * wx +
            (y and 255) * wy
        ) / div

        return (r shl 16) or (g shl 8) or b
    }

    private fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        format: Int,
        first: ByteArray?
    ): ByteArray {
        val body = ByteArrayOutputStream()

        var px = pixels
        var w = width
        var h = height
        var levels = 0

        while (true) {
            val layer = if (
                levels == 0 && first != null
            ) {
                first
            } else if (format == RGBA) {
                rgba(px)
            } else {
                dxt(px, w, h, format)
            }

            body.write(layer, 0, layer.size)
            levels++

            if (w == 1 && h == 1) break

            val nw = maxOf(1, w / 2)
            val nh = maxOf(1, h / 2)
            val next = IntArray(nw * nh)

            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0

                    for (dy in 0 until 2) {
                        for (dx in 0 until 2) {
                            val sx = minOf(
                                w - 1, x * 2 + dx
                            )

                            val sy = minOf(
                                h - 1, y * 2 + dy
                            )

                            val c = px[sy * w + sx]

                            a += c ushr 24
                            r += (c ushr 16) and 255
                            g += (c ushr 8) and 255
                            b += c and 255
                            n++
                        }
                    }

                    next[y * nw + x] =
                        ((a / n) shl 24) or
                        ((r / n) shl 16) or
                        ((g / n) shl 8) or
                        (b / n)
                }
            }

            px = next
            w = nw
            h = nh
        }

        val header = ByteArray(128)

        put32(header, 0, 0x20534444)
        put32(header, 4, 124)

        put32(
            header,
            8,
            0x1 or 0x2 or 0x4 or
                0x1000 or 0x20000 or 0x80000
        )

        put32(header, 12, height)
        put32(header, 16, width)
        put32(header, 20, levelSize(format, width, height))
        put32(header, 28, levels)
        put32(header, 76, 32)

        if (format == RGBA) {
            put32(header, 80, 0x41)
            put32(header, 88, 32)
            put32(header, 92, 0xFF0000)
            put32(header, 96, 0xFF00)
            put32(header, 100, 0xFF)
            put32(header, 104, 0xFF000000.toInt())
        } else {
            put32(header, 80, 4)
            put32(header, 84, format)
        }

        put32(
            header,
            108,
            0x1000 or 0x400000 or 0x8
        )

        val bytes = body.toByteArray()

        return ByteArray(128 + bytes.size).also {
            System.arraycopy(header, 0, it, 0, 128)
            System.arraycopy(bytes, 0, it, 128, bytes.size)
        }
    }

    private fun rgba(pixels: IntArray): ByteArray =
        ByteArray(pixels.size * 4).also {
            for (i in pixels.indices) {
                put32(it, i * 4, pixels[i])
            }
        }

    private fun to565(
        r: Int,
        g: Int,
        b: Int
    ): Int =
        (((r * 31 + 127) / 255) shl 11) or
        (((g * 63 + 127) / 255) shl 5) or
        ((b * 31 + 127) / 255)

    private fun dist(x: Int, y: Int): Int {
        val r =
            ((x ushr 16) and 255) -
            ((y ushr 16) and 255)

        val g =
            ((x ushr 8) and 255) -
            ((y ushr 8) and 255)

        val b =
            (x and 255) -
            (y and 255)

        return r * r + g * g + b * b
    }

    private fun dxt(
        pixels: IntArray,
        width: Int,
        height: Int,
        format: Int
    ): ByteArray {
        val blocksWide = maxOf(1, (width + 3) / 4)
        val blocksHigh = maxOf(1, (height + 3) / 4)
        val bytes = if (format == DXT1) 8 else 16

        val out = ByteArray(
            blocksWide * blocksHigh * bytes
        )

        val block = IntArray(16)

        for (by in 0 until blocksHigh) {
            for (bx in 0 until blocksWide) {
                for (y in 0 until 4) {
                    for (x in 0 until 4) {
                        block[y * 4 + x] = pixels[
                            minOf(
                                height - 1,
                                by * 4 + y
                            ) * width +
                            minOf(
                                width - 1,
                                bx * 4 + x
                            )
                        ]
                    }
                }

                val offset =
                    (by * blocksWide + bx) * bytes

                if (format == DXT3) {
                    for (i in 0 until 16) {
                        val alpha = (
                            (block[i] ushr 24) * 15 + 127
                        ) / 255

                        out[offset + i / 2] = (
                            out[offset + i / 2].toInt() or
                            (
                                alpha shl
                                ((i and 1) * 4)
                            )
                        ).toByte()
                    }
                } else if (format == DXT5) {
                    alphaBlock(block, out, offset)
                }

                colourBlock(
                    block,
                    out,
                    if (format == DXT1) {
                        offset
                    } else {
                        offset + 8
                    },
                    format == DXT1
                )
            }
        }

        return out
    }

    private fun alphaBlock(
        block: IntArray,
        out: ByteArray,
        offset: Int
    ) {
        var lo = 255
        var hi = 0

        for (color in block) {
            lo = minOf(lo, color ushr 24)
            hi = maxOf(hi, color ushr 24)
        }

        out[offset] = hi.toByte()
        out[offset + 1] = lo.toByte()

        if (hi == lo) return

        val palette = alphaPalette(hi, lo)

        var bits = 0L

        for (i in 0 until 16) {
            val value = block[i] ushr 24
            var best = 0
            var bestDistance = 1 shl 30

            for (index in 0 until 8) {
                val distance =
                    kotlin.math.abs(
                        palette[index] - value
                    )

                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }

            bits = bits or (
                best.toLong() shl (3 * i)
            )
        }

        for (i in 0 until 6) {
            out[offset + 2 + i] =
                (bits ushr (8 * i)).toByte()
        }
    }

    private fun colourBlock(
        block: IntArray,
        out: ByteArray,
        offset: Int,
        dxt1: Boolean
    ) {
        var holes = false

        val min = intArrayOf(255, 255, 255)
        val max = intArrayOf(0, 0, 0)

        for (color in block) {
            if (dxt1 && (color ushr 24) < 128) {
                holes = true
                continue
            }

            val channels = intArrayOf(
                (color ushr 16) and 255,
                (color ushr 8) and 255,
                color and 255
            )

            for (k in 0 until 3) {
                min[k] = minOf(min[k], channels[k])
                max[k] = maxOf(max[k], channels[k])
            }
        }

        if (min[0] > max[0]) {
            min.fill(0)
            max.fill(0)
        }

        for (k in 0 until 3) {
            val inset =
                (max[k] - min[k]) / 16

            min[k] += inset
            max[k] -= inset
        }

        var c0 = to565(
            max[0], max[1], max[2]
        )

        var c1 = to565(
            min[0], min[1], min[2]
        )

        val palette = IntArray(4)

        if (holes) {
            if (c0 > c1) {
                val tmp = c0
                c0 = c1
                c1 = tmp
            }

            val e0 = expand565(c0)
            val e1 = expand565(c1)

            palette[0] = e0
            palette[1] = e1
            palette[2] = mix(e0, e1, 1, 1, 2)
        } else {
            if (c0 < c1) {
                val tmp = c0
                c0 = c1
                c1 = tmp
            }

            val e0 = expand565(c0)
            val e1 = expand565(c1)

            palette[0] = e0
            palette[1] = e1
            palette[2] = mix(e0, e1, 2, 1, 3)
            palette[3] = mix(e0, e1, 1, 2, 3)
        }

        var bits = 0

        if (c0 != c1 || holes) {
            for (i in 0 until 16) {
                val color = block[i] and 0xFFFFFF
                var best = 0

                if (
                    holes &&
                    (block[i] ushr 24) < 128
                ) {
                    best = 3
                } else {
                    var bestDistance = Int.MAX_VALUE

                    for (
                        index in 0 until
                            if (holes) 3 else 4
                    ) {
                        val distance = dist(
                            color,
                            palette[index]
                        )

                        if (distance < bestDistance) {
                            bestDistance = distance
                            best = index
                        }
                    }
                }

                bits = bits or (
                    best shl (2 * i)
                )
            }
        }

        out[offset] = c0.toByte()
        out[offset + 1] = (c0 ushr 8).toByte()
        out[offset + 2] = c1.toByte()
        out[offset + 3] = (c1 ushr 8).toByte()

        put32(out, offset + 4, bits)
    }
}
