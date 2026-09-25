package com.moontvplus.nativetv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ReleaseInfo(val version: String, val url: String, val size: Long)

class Updater(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/${BuildConfig.RELEASE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "无法检查更新")
            val release = JSONObject(response.body?.string() ?: "{}")
            val version = release.optString("tag_name").removePrefix("v")
            if (version.isBlank() || version == BuildConfig.VERSION_NAME) return@withContext null
            val assets = release.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk") && name.contains("native", ignoreCase = true)) {
                    return@withContext ReleaseInfo(version, asset.getString("browser_download_url"), asset.optLong("size"))
                }
            }
            null
        }
    }

    suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "moontvplus-native-${release.version}.apk")
        val request = Request.Builder().url(release.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "下载更新失败")
            val body = response.body ?: error("更新文件为空")
            file.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
        if (release.size > 0 && file.length() != release.size) { file.delete(); error("更新文件不完整") }
        if (file.length() < 100_000) { file.delete(); error("更新文件无效") }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION") val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        if (archive?.packageName != context.packageName) { file.delete(); error("更新包应用 ID 不匹配") }
        @Suppress("DEPRECATION") val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() } else @Suppress("DEPRECATION") archive.signatures?.map { it.toCharsString() }
        val currentSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() } else @Suppress("DEPRECATION") installed.signatures?.map { it.toCharsString() }
        if (archiveSigners.isNullOrEmpty() || archiveSigners != currentSigners) { file.delete(); error("更新包签名不匹配") }
        file
    }

    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
