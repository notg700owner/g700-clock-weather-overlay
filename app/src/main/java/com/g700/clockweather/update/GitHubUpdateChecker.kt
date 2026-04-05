package com.g700.clockweather.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.g700.clockweather.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RemoteUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String?,
    val publishedAt: String?
)

data class InstalledBuild(
    val versionCode: Int,
    val versionName: String
)

class GitHubUpdateChecker(private val context: Context) {
    sealed interface UpdateCheckResult {
        data class Available(val manifest: RemoteUpdateManifest, val installedBuild: InstalledBuild) : UpdateCheckResult
        data class UpToDate(val manifest: RemoteUpdateManifest, val installedBuild: InstalledBuild) : UpdateCheckResult
        data class Error(val message: String, val installedBuild: InstalledBuild?) : UpdateCheckResult
    }

    sealed interface InstallResult {
        data object Started : InstallResult
        data class Error(val message: String) : InstallResult
    }

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val installedBuild = installedBuild()
        runCatching {
            val connection = openJsonConnection(metadataUrl())
            connection.requestMethod = "GET"
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                return@runCatching UpdateCheckResult.Error(
                    "Update feed returned HTTP $statusCode${if (errorBody.isNotBlank()) ": $errorBody" else ""}",
                    installedBuild = installedBuild
                )
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val manifest = RemoteUpdateManifest(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                notes = json.optString("notes").takeIf { it.isNotBlank() },
                publishedAt = json.optString("publishedAt").takeIf { it.isNotBlank() }
            )
            if (manifest.versionCode > installedBuild.versionCode) {
                UpdateCheckResult.Available(manifest, installedBuild)
            } else {
                UpdateCheckResult.UpToDate(manifest, installedBuild)
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: error.javaClass.simpleName, installedBuild)
        }
    }

    suspend fun downloadAndInstall(manifest: RemoteUpdateManifest): InstallResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return@withContext InstallResult.Error("Enable install permission for this app first.")
        }

        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "g700-clock-weather-${manifest.versionCode}.apk")
            downloadFile(manifest.apkUrl, apkFile)

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            InstallResult.Started
        }.getOrElse { error ->
            InstallResult.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun metadataUrl(): String {
        return uncachedUrl(
            "https://raw.githubusercontent.com/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}/${BuildConfig.UPDATE_BRANCH}/${BuildConfig.UPDATE_METADATA_PATH}"
        )
    }

    private fun downloadFile(sourceUrl: String, destination: File) {
        val connection = openBinaryConnection(uncachedUrl(sourceUrl))
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
    }

    private fun installedBuild(): InstalledBuild {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return InstalledBuild(
            versionCode = versionCode,
            versionName = info.versionName ?: BuildConfig.VERSION_NAME
        )
    }

    private fun openJsonConnection(url: String): HttpURLConnection {
        return openConnection(url).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun openBinaryConnection(url: String): HttpURLConnection {
        return openConnection(url).apply {
            requestMethod = "GET"
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("User-Agent", "G700ClockWeather/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                setRequestProperty("If-Modified-Since", "0")
            }
        }
    }

    private fun uncachedUrl(url: String): String {
        val joiner = if (url.contains("?")) "&" else "?"
        return "${url}${joiner}ts=${System.currentTimeMillis()}"
    }
}
