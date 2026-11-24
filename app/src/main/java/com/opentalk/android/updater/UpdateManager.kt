package com.opentalk.android.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.opentalk.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileName: String,
    val publishedAt: String = ""
)

object UpdateManager {

    const val DEFAULT_REPO = "ExiumHub/OpenTalk"
    private const val PREFS_NAME = "opentalk_update_prefs"
    private const val KEY_REPO = "update_repo_slug"

    fun getRepoSlug(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REPO, DEFAULT_REPO)?.trim()?.ifEmpty { DEFAULT_REPO } ?: DEFAULT_REPO
    }

    fun setRepoSlug(context: Context, repo: String) {
        val clean = repo.trim().removePrefix("https://github.com/").removePrefix("github.com/").trim('/')
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REPO, clean.ifEmpty { DEFAULT_REPO })
            .apply()
    }

    /**
     * Checks GitHub Releases API for the latest published release.
     * Returns UpdateInfo if a newer version is available, or null if the current version is up to date.
     */
    suspend fun checkForUpdate(context: Context? = null, repoSlug: String = DEFAULT_REPO): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val targetRepo = (if (context != null) getRepoSlug(context) else repoSlug).trim()
                .removePrefix("https://github.com/").removePrefix("github.com/").trim('/')
            val apiUrl = "https://api.github.com/repos/$targetRepo/releases/latest"
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "OpenTalk-Android-Updater")

            val code = conn.responseCode
            if (code == 404) {
                return@withContext Result.failure(Exception("No releases found on GitHub repository ($targetRepo)."))
            }
            if (code != 200) {
                return@withContext Result.failure(Exception("GitHub API responded with code $code: ${conn.responseMessage}"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val releaseNotes = json.optString("body", "").trim()
            val publishedAt = json.optString("published_at", "").take(10)

            val assets = json.optJSONArray("assets")
            var apkDownloadUrl = ""
            var apkFileName = "OpenTalk-$tagName.apk"

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        apkFileName = name
                        break
                    }
                }
            }

            if (apkDownloadUrl.isBlank()) {
                return@withContext Result.failure(Exception("Release $tagName was found, but no .apk asset was uploaded to it."))
            }

            val currentVer = BuildConfig.VERSION_NAME
            val isNewer = isVersionNewer(remoteVersion = tagName, currentVersion = currentVer)

            if (isNewer) {
                Result.success(
                    UpdateInfo(
                        tagName = tagName,
                        releaseNotes = releaseNotes.ifBlank { "Performance improvements and bug fixes." },
                        downloadUrl = apkDownloadUrl,
                        fileName = apkFileName,
                        publishedAt = publishedAt
                    )
                )
            } else {
                Result.success(null) // Already up to date
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compares remote version (e.g. "v2.1.1" or "2.2.0") with current local version (e.g. "2.1.0").
     */
    fun isVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        return try {
            val rParts = cleanVersionString(remoteVersion)
            val cParts = cleanVersionString(currentVersion)

            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            false
        } catch (_: Exception) {
            remoteVersion.trimStart('v', 'V') != currentVersion.trimStart('v', 'V')
        }
    }

    private fun cleanVersionString(v: String): List<Int> {
        val clean = v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .substringBefore(" ")
        return clean.split(".")
            .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
    }

    /**
     * Downloads the APK file to app cache with real-time download progress tracking (0.0 to 1.0).
     * Handles HTTP 301/302 redirects (required for GitHub Release CDN / AWS S3 links).
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }
            val destinationFile = File(updatesDir, fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            var currentUrl = downloadUrl
            var connection: HttpURLConnection
            var redirectCount = 0

            // Follow redirects manually to handle GitHub -> AWS S3 redirect cleanly
            while (true) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "OpenTalk-Android-Updater")

                val status = connection.responseCode
                if (status in 300..399) {
                    val redirectUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (redirectUrl != null && redirectCount < 6) {
                        currentUrl = redirectUrl
                        redirectCount++
                        continue
                    }
                }
                break
            }

            val fileLength = connection.contentLength
            val inputStream: InputStream = connection.inputStream
            val outputStream = FileOutputStream(destinationFile)

            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (fileLength > 0) {
                    val progress = (totalBytesRead.toFloat() / fileLength).coerceIn(0f, 1f)
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            Result.success(destinationFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Installs the downloaded APK via FileProvider & Android PackageInstaller Intent.
     */
    suspend fun installApk(context: Context, apkFile: File): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return@withContext Result.failure(Exception("APK file does not exist or is empty."))
        }

        return@withContext try {
            // Check Unknown Sources permission on Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(manageIntent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {}
                    }
                    return@withContext Result.failure(Exception("Please allow install permission from this source"))
                }
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(intent)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
