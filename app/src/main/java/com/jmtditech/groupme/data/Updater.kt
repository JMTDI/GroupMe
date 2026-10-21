package com.jmtditech.groupme.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@Serializable
private data class GhAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
    val digest: String? = null,
)

@Serializable
private data class GhRelease(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

data class UpdateInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String?,
)

/** Checks GitHub Releases for a newer APK, downloads it and hands it to Android's installer. */
class Updater(
    private val ctx: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** Latest published (non-draft, non-prerelease) release that has an .apk asset, or null. */
    suspend fun fetchLatest(repo: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GroupMe-Android")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) error("GitHub returned HTTP ${r.code}")
            val rel = json.decodeFromString(GhRelease.serializer(), r.body?.string() ?: error("Empty response"))
            if (rel.draft || rel.tag_name.isBlank()) return@withContext null
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            UpdateInfo(
                tag = rel.tag_name,
                title = rel.name?.takeIf { it.isNotBlank() } ?: rel.tag_name,
                notes = rel.body.orEmpty(),
                apkUrl = apk.browser_download_url,
                size = apk.size,
                sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.substringAfter(':'),
            )
        }
    }

    /** Compares dotted numbers: "v1.10" is newer than "1.9". */
    fun isNewer(tag: String, installed: String): Boolean {
        fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = nums(tag)
        val b = nums(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the APK into the app's cache, verifying GitHub's SHA-256 when it is provided. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, "GroupMe-${info.tag}.apk")
        val req = Request.Builder().url(info.apkUrl).header("User-Agent", "GroupMe-Android").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Download failed: HTTP ${r.code}")
            val body = r.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !got.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                error("The downloaded file failed its checksum")
            }
        }
        out
    }

    /**
     * Android only installs an update signed with the same key as the installed app. Returns false if the
     * downloaded APK is signed differently, or null when that can't be determined (Android 8-8.1).
     */
    fun sameSigner(file: File): Boolean? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            val pm = ctx.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)?.signingInfo ?: return null
            val installed = pm.getPackageInfo(ctx.packageName, flags).signingInfo ?: return null
            fun sigs(s: android.content.pm.SigningInfo) = s.apkContentsSigners.map { it.toCharsString() }.toSet()
            sigs(archive) == sigs(installed)
        }.getOrNull()
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Settings page where the user allows this app to install packages. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
