package de.plmail.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import de.plmail.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer build on the repository's GitHub Releases, for this app's flavour. */
data class AvailableUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

/**
 * BearerMail: updates the app from the repository's own GitHub Releases.
 *
 * The release workflow publishes `plmail-<version>-<flavour>.apk`, signed with the release key, for
 * every tag. This asks GitHub for the latest release, picks the APK for the flavour that is running
 * (`foss` or `google` — they are different apps on the phone), and hands it to Android's own
 * installer, which asks the user to confirm. Nothing is installed without that tap.
 *
 * Android refuses an update signed with a different key, so this only works between release builds:
 * a debug build never checks.
 */
object AppUpdater {

    private const val REPO = "DanversKara/pl_mail_android_bearermail"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TIMEOUT_MS = 15_000

    /**
     * Whether this install updates itself from GitHub at all: never for debug builds, and never for
     * Google Play installs — Play delivers updates itself, and a GitHub APK cannot install over a
     * Play-signed app anyway.
     */
    fun enabled(context: Context): Boolean = !BuildConfig.DEBUG && !isPlayInstall(context)

    /**
     * True when this install came from Google Play (installer package com.android.vending). An
     * unknown installer, or a lookup failure, means "not Play".
     */
    fun isPlayInstall(context: Context): Boolean {
        return try {
            val installer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager
                        .getInstallSourceInfo(context.packageName)
                        .installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            installer == "com.android.vending"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The latest release if it is newer than this build, otherwise null. Throws on network errors.
     */
    suspend fun latest(): AvailableUpdate? =
        withContext(Dispatchers.IO) {
            val connection =
                (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "BearerMail/${BuildConfig.VERSION_NAME}")
                }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                parse(json, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
            } finally {
                connection.disconnect()
            }
        }

    internal fun parse(release: JSONObject, current: String, flavour: String): AvailableUpdate? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val version = release.optString("tag_name").removePrefix("v")
        if (version.isEmpty() || compareVersions(version, current) <= 0) return null

        val assets = release.optJSONArray("assets") ?: return null
        val suffix = "-$flavour.apk"
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(suffix)) {
                return AvailableUpdate(
                    version = version,
                    notes = release.optString("body").trim(),
                    apkUrl = asset.getString("browser_download_url"),
                    apkSize = asset.optLong("size"),
                    pageUrl = release.optString("html_url"),
                )
            }
        }
        return null
    }

    /**
     * `1.2.10` > `1.2.9`; a pre-release suffix (`-rc1`) is ignored, as the versionCode ignores it.
     */
    internal fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Downloads the APK into the cache, reporting 0..1 progress. Returns the file. */
    suspend fun download(
        context: Context,
        update: AvailableUpdate,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Older downloads are of no further use.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "bearermail-${update.version}.apk")

            // GitHub answers with a redirect to its storage host; HttpURLConnection follows it
            // because both are https.
            val connection =
                (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS * 2
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "BearerMail/${BuildConfig.VERSION_NAME}")
                }
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Download failed: HTTP ${connection.responseCode}"
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                connection.disconnect()
            }
            target
        }

    /**
     * Hands the APK to Android's package installer. The system then shows its own "Do you want to
     * update this app?" screen — see [UpdateInstallReceiver] — and the app restarts on the new
     * version once the user agrees.
     */
    suspend fun install(context: Context, apk: File) =
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setAppPackageName(context.packageName)
                        setSize(apk.length())
                    }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val status =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, UpdateInstallReceiver::class.java),
                        // MUTABLE: the installer fills in the status and the confirmation intent.
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(status.intentSender)
            }
        }
}
