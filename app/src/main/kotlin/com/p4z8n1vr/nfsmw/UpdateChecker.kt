package com.p4z8n1vr.nfsmw

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker private constructor() {

    // Preserve direct Java field access during migration.
    class Release {
        @JvmField var tag: String = ""
        @JvmField var name: String = ""
        @JvmField var page: String = ""
        @JvmField var apk: String? = null
        @JvmField var changes: String = ""
    }

    fun interface Listener {
        fun onResult(newer: Release?, failed: Boolean)
    }

    companion object {
        const val REPO = "RanggaCasper/MostWanted-Android"

        private const val LATEST =
            "https://api.github.com/repos/$REPO/releases/latest"

        private const val TAG = "MostWantedUpdate"
        private const val TIMEOUT_MS = 8_000
        private const val PREFS = "updates"
        private const val SKIPPED = "skipped_tag"

        @JvmStatic
        fun check(
            context: Context,
            manual: Boolean,
            listener: Listener
        ) {
            if (REPO.isEmpty()) return

            val app = context.applicationContext
            val prefs = app.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            )
            val installed = installedVersion(app)
            val main = Handler(Looper.getMainLooper())

            Thread({
                var newer: Release? = null
                var failed = false

                try {
                    val release = fetch()

                    val skipped = release != null &&
                        !manual &&
                        release.tag == prefs.getString(
                            SKIPPED, null
                        )

                    if (
                        release != null &&
                        !skipped &&
                        compare(release.tag, installed) > 0
                    ) {
                        Log.i(
                            TAG,
                            "update ${release.tag} over $installed"
                        )
                        newer = release
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "no update check: $e")
                    failed = true
                }

                main.post {
                    listener.onResult(newer, failed)
                }
            }, "UpdateCheck").start()
        }

        @JvmStatic
        fun skip(context: Context, tag: String) {
            context.applicationContext
                .getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE
                )
                .edit()
                .putString(SKIPPED, tag)
                .apply()
        }

        @JvmStatic
        fun installedVersion(context: Context): String {
            return try {
                context.packageManager.getPackageInfo(
                    context.packageName, 0
                ).versionName ?: "0"
            } catch (_: PackageManager.NameNotFoundException) {
                "0"
            }
        }

        private fun fetch(): Release? {
            val connection = URL(LATEST)
                .openConnection() as HttpURLConnection

            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty(
                "Accept", "application/vnd.github+json"
            )
            connection.setRequestProperty(
                "User-Agent", "MostWanted-Android"
            )

            try {
                if (
                    connection.responseCode !=
                    HttpURLConnection.HTTP_OK
                ) return null

                val data = connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                val json = JSONObject(data)
                val release = Release()

                release.tag = json.getString("tag_name")
                release.name = json.optString(
                    "name", release.tag
                ).ifEmpty { release.tag }

                release.page = json.optString(
                    "html_url",
                    "https://github.com/$REPO/releases/latest"
                )

                val assets = json.optJSONArray("assets")

                if (assets != null) {
                    for (index in 0 until assets.length()) {
                        val asset = assets.getJSONObject(index)

                        if (
                            asset.optString("name")
                                .lowercase()
                                .endsWith(".apk")
                        ) {
                            release.apk = asset.optString(
                                "browser_download_url", ""
                            ).takeIf { it.isNotEmpty() }
                            break
                        }
                    }
                }

                release.changes = changes(
                    json.optString("body", "")
                )

                return release
            } finally {
                connection.disconnect()
            }
        }

        private fun changes(body: String): String {
            val at = body.indexOf("## What's Changed")
            if (at < 0) return ""

            val lines = StringBuilder()
            var count = 0

            for (
                raw in body.substring(at).split(
                    Regex("\\r?\\n")
                )
            ) {
                val line = raw.trim()

                if (
                    line.startsWith("**Full Changelog**") ||
                    (
                        line.startsWith("## ") &&
                        lines.isNotEmpty()
                    )
                ) break

                if (
                    !line.startsWith("* ") &&
                    !line.startsWith("- ")
                ) continue

                val cleaned = line.drop(2)
                    .replace(
                        Regex(""" by @\S+ in https://\S+$"""),
                        ""
                    )
                    .replace(
                        Regex(""" in https://\S+$"""),
                        ""
                    )

                count++

                if (count > 10) {
                    lines.append("• …\n")
                    break
                }

                lines.append("• ")
                    .append(cleaned)
                    .append('\n')
            }

            return lines.toString().trim()
        }

        @JvmStatic
        fun compare(a: String?, b: String?): Int {
            val left = parts(a)
            val right = parts(b)

            for (
                index in 0 until maxOf(
                    left.size, right.size
                )
            ) {
                val x = left.getOrElse(index) { 0 }
                val y = right.getOrElse(index) { 0 }

                if (x != y) return x.compareTo(y)
            }

            return 0
        }

        private fun parts(value: String?): IntArray {
            if (value == null) return intArrayOf()

            var cleaned = value.trim()

            if (
                cleaned.startsWith(
                    "v", ignoreCase = true
                )
            ) {
                cleaned = cleaned.substring(1)
            }

            cleaned = cleaned.substringBefore('-')

            return cleaned.split('.').map { token ->
                token.replace(
                    Regex("""\D.*"""), ""
                ).toIntOrNull() ?: 0
            }.toIntArray()
        }
    }
}
