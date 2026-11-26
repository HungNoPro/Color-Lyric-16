package shop.terramoor.github.io.settings

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal object GitHubUpdates {
    const val UPSTREAM_REPOSITORY = "Luckyumimi/Color-lyric"
    const val UPSTREAM_REPOSITORY_URL = "https://github.com/$UPSTREAM_REPOSITORY"
    const val REPOSITORY = "HungNoPro/Color-Lyric-16"
    const val REPOSITORY_URL = "https://github.com/$REPOSITORY"
    const val RELEASES_URL = "$REPOSITORY_URL/releases"
    private const val API_URL = "https://api.github.com/repos/$REPOSITORY"
    private const val TIMEOUT_MS = 5_000
    private const val DOWNLOAD_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private val releaseTag = Regex("v?(?:([0-9]+)-)?([0-9]+(?:\\.[0-9]+)*)", RegexOption.IGNORE_CASE)

    enum class Comparison { NEWER, UP_TO_DATE, UNKNOWN }

    sealed interface Result {
        data object Idle : Result
        data object Checking : Result
        data object NoRelease : Result
        data object Failed : Result
        data class Latest(
            val tag: String,
            val comparison: Comparison,
            val releaseNotes: String = "",
            val releaseUrl: String = RELEASES_URL,
            val apkDownloadUrl: String? = null,
            val apkName: String? = null,
            val apkSizeBytes: Long = -1L,
        ) : Result
    }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState {
            val progress: Float?
                get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
        }
        data class Ready(val apkFile: File) : DownloadState
        data class Failed(val message: String? = null) : DownloadState
    }

    suspend fun check(versionCode: Long, versionName: String): Result = withContext(Dispatchers.IO) {
        try {
            val (status, body) = request("/releases/latest")
            when {
                status == HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name")
                    val htmlUrl = json.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_URL
                    val notes = formatReleaseNotes(json.optString("body"))
                    val (apkUrl, apkName, apkSize) = findApkAsset(json)
                    if (tag.isBlank()) Result.Failed else Result.Latest(
                        tag = tag,
                        comparison = compare(tag, versionCode, versionName),
                        releaseNotes = notes,
                        releaseUrl = htmlUrl,
                        apkDownloadUrl = apkUrl,
                        apkName = apkName,
                        apkSizeBytes = apkSize,
                    )
                }
                status == HttpURLConnection.HTTP_NOT_FOUND && request("").first == HttpURLConnection.HTTP_OK -> {
                    Result.NoRelease
                }
                else -> Result.Failed
            }
        } catch (_: IOException) {
            Result.Failed
        } catch (_: JSONException) {
            Result.Failed
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String?,
        expectedSizeBytes: Long = -1L,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile) runCatching { old.delete() }
        }
        val safeName = fileName?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "Color-Lyric-16-update.apk"
        val targetFile = File(updatesDir, safeName)
        val tempFile = File(updatesDir, "$safeName.part")

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(downloadUrl)
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L } ?: expectedSizeBytes
            onProgress(0L, contentLength)
            val buffer = ByteArray(16 * 1024)
            var downloaded = 0L
            var lastNotifyAt = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = SystemClock.uptimeMillis()
                        if (now - lastNotifyAt >= 60L || (contentLength > 0L && downloaded >= contentLength)) {
                            lastNotifyAt = now
                            onProgress(downloaded, contentLength)
                        }
                    }
                    output.flush()
                }
            }
            if (downloaded <= 0L) throw IOException("Empty APK file")
            onProgress(downloaded, contentLength.takeIf { it > 0L } ?: downloaded)
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = DOWNLOAD_TIMEOUT_MS
            conn.readTimeout = DOWNLOAD_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Color-Lyric-16")
            conn.setRequestProperty("Accept", "application/octet-stream")
            val code = conn.responseCode
            if (code in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without Location header")
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects")
    }

    private fun findApkAsset(json: JSONObject): Triple<String?, String?, Long> {
        val assets = json.optJSONArray("assets") ?: return Triple(null, null, -1L)
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                val size = asset.optLong("size", -1L)
                return Triple(url, name, size)
            }
        }
        return Triple(null, null, -1L)
    }

    private fun request(path: String): Pair<Int, String> {
        val connection = URL("$API_URL$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Color-Lyric-16")
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK && path.isNotEmpty()) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                ""
            }
            return status to body
        } finally {
            connection.disconnect()
        }
    }

    internal fun compare(tag: String, versionCode: Long, versionName: String): Comparison {
        val remote = releaseTag.matchEntire(tag) ?: return Comparison.UNKNOWN
        val code = remote.groupValues[1]
        if (code.isNotEmpty()) {
            val remoteCode = code.toLongOrNull() ?: return Comparison.UNKNOWN
            return if (remoteCode > versionCode) Comparison.NEWER else Comparison.UP_TO_DATE
        }
        val local = releaseTag.matchEntire(versionName) ?: return Comparison.UNKNOWN
        val remoteParts = remote.groupValues[2].split('.')
        val localParts = local.groupValues[2].split('.')
        for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
            val remoteNumber = remoteParts.getOrElse(index) { "0" }.toLongOrNull() ?: return Comparison.UNKNOWN
            val localNumber = localParts.getOrElse(index) { "0" }.toLongOrNull() ?: return Comparison.UNKNOWN
            if (remoteNumber > localNumber) return Comparison.NEWER
            if (remoteNumber < localNumber) return Comparison.UP_TO_DATE
        }
        return Comparison.UP_TO_DATE
    }

    private fun formatReleaseNotes(raw: String): String {
        val cleaned = raw.replace("\r\n", "\n").trim()
        if (cleaned.isEmpty()) return ""
        return if (cleaned.length > 420) cleaned.take(417).trimEnd() + "…" else cleaned
    }
}
