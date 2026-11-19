package com.p4z8n1vr.nfsmw

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Environment
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.function.Consumer

// Converts and installs cars, then rebuilds the overlays consumed by the game.
internal object ModInstaller {
    private const val ZDIR = "NFS/ZDIR.BIN"
    private const val LIST = "cars.txt"
    private const val TYPES = "car_types.txt"
    private const val REMOVED = "cars_removed.txt"
    private const val GARAGE = "garage"
    private const val INFO = "info.txt"
    private const val GEOMETRY = "geometry.bin"
    private const val TEXTURES = "textures.bin"
    private const val SCRIPT = "script.nfsms"
    private const val LOGO = "logo.dxt"
    private const val BUILT = ".built"

    const val BUILD = 2

    class Installed {
        @JvmField val done: MutableList<String> = ArrayList()
        @JvmField val failed: MutableList<String> = ArrayList()
    }

    @JvmStatic
    fun modsDir(): File = File(
        File(Environment.getExternalStorageDirectory(), MostWantedActivity.SHARED),
        "mods"
    )

    // Search the same directories, in the same priority order, as the Java code.
    @JvmStatic
    fun findGame(context: Context): File? {
        val shared = File(Environment.getExternalStorageDirectory(), MostWantedActivity.SHARED)
        val folders = ArrayList<File>()
        folders.add(shared)
        folders.add(File(shared, "assets"))
        for (card in MostWantedActivity.cardFolders(context)) {
            folders.add(card)
            folders.add(File(card, "assets"))
        }
        context.getExternalFilesDir(null)?.let { folders.add(File(it, "assets")) }

        for (dir in folders) {
            if (File(dir, "default.xbe").exists()) return dir
            for (file in dir.listFiles()?.sorted().orEmpty()) {
                if (file.isFile && file.name.lowercase(Locale.ROOT).endsWith(".iso")) {
                    return file
                }
            }
        }
        return null
    }

    @JvmStatic
    fun kept(): List<CarConverter.Kept> {
        val result = ArrayList<CarConverter.Kept>()
        val folders = File(modsDir(), GARAGE).listFiles()?.filter { it.isDirectory }
            ?: return result
        val ordered = folders.sortedWith(
            compareBy<File> { File(it, INFO).lastModified() }.thenBy { it.name }
        )
        for (dir in ordered) {
            try {
                val lines = Files.readAllLines(File(dir, INFO).toPath(), Charsets.UTF_8)
                val car = CarConverter.Kept()
                car.car = lines[0]
                car.title = lines[1]
                car.manufacturer = lines[2]
                car.logoWidth = lines[3].toInt()
                car.logoHeight = lines[4].toInt()
                car.logoFormat = lines[5].toInt()
                car.geometry = File(dir, GEOMETRY)
                car.textures = File(dir, TEXTURES)
                val script = File(dir, SCRIPT)
                val logo = File(dir, LOGO)
                if (script.exists()) car.script = Files.readAllBytes(script.toPath())
                if (logo.exists()) car.logo = Files.readAllBytes(logo.toPath())
                if (car.geometry.exists() && car.textures.exists()) result.add(car)
            } catch (_: IOException) {
                // Ignore incomplete installs.
            } catch (_: RuntimeException) {
                // Ignore invalid metadata.
            }
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun install(
        context: Context,
        archive: File,
        pageTitle: String?,
        log: Consumer<String>
    ): Installed {
        val game = findGame(context) ?: throw IOException("no game")
        val spool = File(File(context.cacheDir, "mods"), "spool")
        try {
            return installFromGame(context, game, archive, spool, pageTitle, log)
        } finally {
            ModArchive.clear(spool)
        }
    }

    @Throws(Exception::class)
    private fun installFromGame(
        context: Context,
        game: File,
        archive: File,
        spool: File,
        pageTitle: String?,
        log: Consumer<String>
    ): Installed {
        val files = ModArchive.read(archive, spool, log)
        val paths = CarConverter.carPaths(files.keys)
        val result = Installed()

        if (TexturePacks.packPaths(files.keys).isNotEmpty()) {
            result.done.addAll(TexturePacks.install(context, files, log))
            if (paths.isEmpty()) {
                if (result.done.isEmpty()) {
                    throw CarConverter.Failure(
                        CarConverter.Failure.Reason.NO_CAR,
                        "a texture pack whose images are not in it"
                    )
                }
                return result
            }
        }
        if (paths.isEmpty()) {
            throw CarConverter.Failure(
                CarConverter.Failure.Reason.NO_CAR,
                "no <name>/GEOMETRY.BIN with a TEXTURES.BIN beside it"
            )
        }
        val packs = result.done.size
        var first: CarConverter.Failure? = null

        CarConverter.Disc(game).use { disc ->
            for ((index, path) in paths.withIndex()) {
                val name = CarConverter.carName(path)
                log.accept("car ${index + 1}/${paths.size}: $name")
                try {
                    val pack = CarConverter.convert(
                        disc, CarConverter.carFiles(files, path), path,
                        CarConverter.MAX_TEXTURES_KB, log
                    )
                    if (
                        paths.size == 1 &&
                        pack.title == CarConverter.titleCase(pack.car) &&
                        !pageTitle.isNullOrEmpty()
                    ) {
                        pack.title = pageTitle
                    }
                    keep(pack)
                    result.done.add(pack.title)
                } catch (error: CarConverter.Failure) {
                    log.accept("$name: ${error.message}")
                    if (first == null) first = error
                    result.failed.add(name)
                } catch (_: OutOfMemoryError) {
                    log.accept("$name: out of memory")
                    result.failed.add(name)
                    if (first == null) {
                        first = CarConverter.Failure(
                            CarConverter.Failure.Reason.UNSUPPORTED,
                            "$name: out of memory"
                        )
                    }
                }
            }
        }
        if (result.done.size == packs) {
            if (packs == 0) throw first ?: IOException("no car installed")
            return result
        }
        rebuild(context, game, log)
        return result
    }

    @Throws(Exception::class)
    private fun keep(pack: CarConverter.Package) {
        if (pack.logo == null) drawLogo(pack)
        val folder = File(File(modsDir(), GARAGE), pack.car)
        if (!folder.exists() && kept().size >= CarConverter.MAX_ADDONS) {
            throw CarConverter.Failure(
                CarConverter.Failure.Reason.UNSUPPORTED,
                "more than ${CarConverter.MAX_ADDONS} cars"
            )
        }
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("cannot write to $folder")
        }
        File(folder, INFO).delete()
        File(folder, SCRIPT).delete()
        File(folder, LOGO).delete()
        write(File(folder, GEOMETRY), pack.geometry)
        write(File(folder, TEXTURES), pack.textures)
        pack.script?.let { write(File(folder, SCRIPT), it) }
        pack.logo?.let { write(File(folder, LOGO), it) }
        write(
            File(folder, INFO),
            ("${pack.car}\n${pack.title}\n${pack.manufacturer}\n" +
                "${pack.logoWidth}\n${pack.logoHeight}\n${pack.logoFormat}\n")
                .toByteArray(Charsets.UTF_8)
        )
    }

    private fun drawLogo(pack: CarConverter.Package) {
        val width = 512
        val height = 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setColor(Color.WHITE)
            setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC))
            setTextAlign(Paint.Align.CENTER)
            setTextSize(64f)
        }
        val room = width - 16f
        val wide = paint.measureText(pack.title)
        if (wide > room) paint.setTextSize(64f * room / wide)
        Canvas(bitmap).drawText(
            pack.title,
            width / 2f,
            height / 2f - (paint.ascent() + paint.descent()) / 2f,
            paint
        )
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        CarConverter.setLogo(pack, pixels, width, height)
    }

    @JvmStatic
    fun outdated(): Boolean {
        if (kept().isEmpty()) return false
        return try {
            val marker = File(File(modsDir(), GARAGE), BUILT)
            val number = String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII)
                .trim().toInt()
            number < BUILD
        } catch (_: IOException) {
            true
        } catch (_: NumberFormatException) {
            true
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun refresh(context: Context, log: Consumer<String>) {
        val game = findGame(context) ?: throw IOException("no game")
        rebuild(context, game, log)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun uninstall(context: Context, car: String, log: Consumer<String>) {
        uninstall(context, listOf(car), log)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun uninstall(context: Context, cars: List<String>, log: Consumer<String>) {
        for (car in cars) {
            val folder = File(File(modsDir(), GARAGE), car)
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
        val game = findGame(context) ?: throw IOException("no game")
        rebuild(context, game, log)
    }

    @Throws(Exception::class)
    private fun rebuild(context: Context, game: File, log: Consumer<String>) {
        val dir = modsDir()
        val work = File(dir, ".new")
        if (!work.isDirectory && !work.mkdirs()) {
            throw IOException("cannot write to $dir")
        }
        try {
            val existing = kept()
            var built: CarConverter.Built? = null
            if (existing.isNotEmpty()) {
                CarConverter.Disc(game).use { disc ->
                    for (car in existing) {
                        CarConverter.fitGeometry(disc, car.geometry, log)
                    }
                    built = CarConverter.build(disc, existing, work, log)
                }
                built?.let { output ->
                    for ((name, bytes) in output.files) {
                        if (bytes != null) {
                            write(File(work, name.replace('/', '_')), bytes)
                        }
                    }
                }
            }

            stopGame(context)
            val before = listedKeys()
            removeFiles(dir)
            built?.let { output ->
                val nfs = File(dir, "NFS")
                if (!nfs.isDirectory && !nfs.mkdirs()) {
                    throw IOException("cannot write to $nfs")
                }
                for (name in output.files.keys) {
                    if (name != ZDIR) {
                        move(
                            File(work, name.replace('/', '_')),
                            File(dir, name)
                        )
                    }
                }
            }
            noteRemoved(before, listedKeys())
            if (built != null) {
                move(File(work, ZDIR.replace('/', '_')), File(dir, ZDIR))
            }
            write(
                File(File(dir, GARAGE), BUILT),
                "$BUILD\n".toByteArray(StandardCharsets.US_ASCII)
            )
        } finally {
            work.listFiles()?.forEach { it.delete() }
            work.delete()
        }
    }

    private fun removeFiles(dir: File) {
        File(dir, ZDIR).delete()
        File(dir, LIST).delete()
        File(dir, TYPES).delete()
        File(dir, "car_name.dxt").delete()
        dir.listFiles()?.filter {
            it.name.matches(Regex("name_[0-9a-f]{8}\\.dxt"))
        }?.forEach { it.delete() }
        File(dir, "NFS").listFiles()?.filter {
            it.name.uppercase(Locale.ROOT).matches(Regex("ZZDATA\\d+\\.BIN"))
        }?.forEach { it.delete() }
    }

    @Throws(IOException::class)
    private fun listedKeys(): Set<String> {
        val keys = LinkedHashSet<String>()
        val list = File(modsDir(), LIST)
        if (list.exists()) {
            for (line in Files.readAllLines(list.toPath(), StandardCharsets.ISO_8859_1)) {
                val words = line.trim().split(Regex("\\s+"), limit = 2)
                if (words[0].isNotEmpty()) keys.add(words[0])
            }
        }
        return keys
    }

    @Throws(IOException::class)
    private fun noteRemoved(before: Set<String>, now: Set<String>) {
        val list = File(modsDir(), REMOVED)
        val keys = LinkedHashSet<String>()
        if (list.exists()) {
            for (line in Files.readAllLines(list.toPath(), Charsets.UTF_8)) {
                val name = line.trim()
                if (name.isNotEmpty()) keys.add(name)
            }
        }
        keys.addAll(before)
        keys.removeAll(now)
        if (keys.isEmpty()) {
            list.delete()
            return
        }
        write(list, (keys.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
    }

    @Throws(IOException::class)
    private fun write(file: File, data: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(data)
            output.fd.sync()
        }
    }

    @Throws(IOException::class)
    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) {
            throw IOException("cannot move ${from.name} to $to")
        }
    }

    @JvmStatic
    fun gameRunning(context: Context): Boolean = gamePid(context) != 0

    private fun gamePid(context: Context): Int {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0
        val processes = manager.runningAppProcesses ?: return 0
        for (process in processes) {
            if (process.processName == context.packageName) return process.pid
        }
        return 0
    }

    @JvmStatic
    fun stopGame(context: Context) {
        val pid = gamePid(context)
        if (pid != 0) Process.killProcess(pid)
    }
}
