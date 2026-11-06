package dev.colorcode.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class GitHubRelease(val tag: String, val apkUrl: String)

internal object ReleaseUpdates {
    private val versionPattern = Regex("v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")

    fun isNewer(tag: String, installed: String): Boolean {
        fun parts(value: String): List<Long>? {
            val values = versionPattern.matchEntire(value)?.groupValues?.drop(1) ?: return null
            return values.map { it.toLongOrNull() ?: return null }
        }
        val latest = parts(tag) ?: return false
        val current = parts(installed) ?: return false
        return latest.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }

    fun fetch(): GitHubRelease {
        val connection = (URL("https://api.github.com/repos/evan6509/colorcode/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ColorCode-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned ${connection.responseCode}")
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val tag = release.getString("tag_name")
            if (!tag.startsWith("v") || !versionPattern.matches(tag)) throw IOException("Invalid release version")
            val name = "ColorCode-$tag.apk"
            val url = "https://github.com/evan6509/colorcode/releases/download/$tag/$name"
            val assets = release.getJSONArray("assets")
            if ((0 until assets.length()).none { i ->
                val asset = assets.getJSONObject(i)
                asset.optString("name") == name && asset.optString("browser_download_url") == url
            }) throw IOException("Release APK unavailable")
            return GitHubRelease(tag, url)
        } finally { connection.disconnect() }
    }

    fun download(context: Context, release: GitHubRelease) {
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("ColorCode ${release.tag}")
            .setDescription("Tap the completed download to install the update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                "ColorCode-${release.tag}-${System.currentTimeMillis()}.apk")
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}
