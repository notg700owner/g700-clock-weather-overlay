package com.g700.clockweather.update

import android.content.Context
import android.content.Intent
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

class GitHubUpdateChecker(private val context: Context) {
    sealed interface UpdateCheckResult {
        data class Available(val manifest: RemoteUpdateManifest) : UpdateCheckResult
        data class UpToDate(val manifest: RemoteUpdateManifest) : UpdateCheckResult
        data class Error(val message: String) : UpdateCheckResult
    }

    sealed interface InstallResult {
        data object Started : InstallResult
        data class Error(val message: String) : InstallResult
    }

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(metadataUrl()).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "G700ClockWeather/${BuildConfig.VERSION_NAME}")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                return@runCatching UpdateCheckResult.Error(
                    "Update feed returned HTTP $statusCode${if (errorBody.isNotBlank()) ": $errorBody" else ""}"
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
            if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                UpdateCheckResult.Available(manifest)
            } else {
                UpdateCheckResult.UpToDate(manifest)
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: error.javaClass.simpleName)
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
        return "https://raw.githubusercontent.com/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}/${BuildConfig.UPDATE_BRANCH}/${BuildConfig.UPDATE_METADATA_PATH}"
    }

    private fun downloadFile(sourceUrl: String, destination: File) {
        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "G700ClockWeather/${BuildConfig.VERSION_NAME}")
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
    }
}
