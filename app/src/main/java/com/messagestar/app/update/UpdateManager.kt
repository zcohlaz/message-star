package com.messagestar.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.messagestar.app.BuildConfig
import com.messagestar.app.data.AlertStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class UpdateManager(private val context: Context) {
    val isConfigured: Boolean get() = UpdateManifestParser.isHttpsUrl(BuildConfig.UPDATE_MANIFEST_URL)

    fun isAlerting(): Boolean = AlertStateStore.snapshot(context).isAlerting

    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        check(!isAlerting()) { "强提醒进行中，请稍后检查更新" }
        check(isConfigured) { "更新通道尚未配置" }
        val body = connection(BuildConfig.UPDATE_MANIFEST_URL).use { connection ->
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "更新服务器返回 ${connection.responseCode}" }
            check(UpdateManifestParser.isHttpsUrl(connection.url.toString())) { "更新服务器重定向到了非 HTTPS 地址" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    body.append(buffer, 0, count)
                    check(body.length <= 65_536) { "更新清单过大" }
                }
                check(body.isNotEmpty()) { "更新清单为空" }
                body.toString()
            }
        }
        val manifest = UpdateManifestParser.parse(body, context.packageName)
        if (manifest.versionCode > installedVersionCode()) manifest else null
    }

    suspend fun download(manifest: UpdateManifest, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        check(!isAlerting()) { "强提醒进行中，请稍后下载更新" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "message-star-${manifest.versionCode}.apk")
        if (target.isFile && verifyDigest(target, manifest)) {
            verifyApk(target, manifest)
            onProgress(100)
            return@withContext target
        }
        val temporary = File(directory, "download-incomplete.apk")
        try {
            connection(manifest.apkUrl).use { connection ->
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "下载服务器返回 ${connection.responseCode}" }
                check(UpdateManifestParser.isHttpsUrl(connection.url.toString())) { "下载服务器重定向到了非 HTTPS 地址" }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            check(!isAlerting()) { "强提醒已开始，下载已暂停" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= manifest.sizeBytes) { "安装包大小与清单不符" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            onProgress((received * 100 / manifest.sizeBytes).toInt().coerceAtMost(100))
                        }
                    }
                }
                check(received == manifest.sizeBytes) { "安装包下载不完整" }
                check(digest.digest().toHex() == manifest.sha256.lowercase()) { "安装包 SHA-256 校验失败" }
            }
            verifyApk(temporary, manifest)
            check(!target.exists() || target.delete()) { "无法替换旧的更新缓存" }
            check(temporary.renameTo(target)) { "无法保存安装包" }
            target
        } finally {
            temporary.delete()
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installationPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    fun install(apk: File, manifest: UpdateManifest) {
        check(!isAlerting()) { "强提醒进行中，请稍后安装更新" }
        check(canInstallPackages()) { "请先允许本应用安装更新" }
        check(verifyDigest(apk, manifest)) { "安装包 SHA-256 校验失败" }
        verifyApk(apk, manifest)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeLong(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private fun installedVersionCode(): Long = packageInfo(context.packageName, archive = false).versionCodeLong()

    @Suppress("DEPRECATION")
    private fun packageInfo(pathOrName: String, archive: Boolean): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (archive) {
            context.packageManager.getPackageArchiveInfo(pathOrName, flags)
                ?: error("下载的文件不是有效 APK")
        } else {
            context.packageManager.getPackageInfo(pathOrName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        check(!signatures.isNullOrEmpty()) { "无法读取安装包签名" }
        return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }

    private fun verifyApk(file: File, manifest: UpdateManifest) {
        check(file.isFile && file.length() == manifest.sizeBytes) { "安装包文件不完整" }
        val archive = packageInfo(file.absolutePath, archive = true)
        check(archive.packageName == context.packageName) { "安装包的应用包名不匹配" }
        check(archive.versionCodeLong() == manifest.versionCode && archive.versionCodeLong() > installedVersionCode()) { "安装包版本不匹配" }
        check(signatures(archive) == signatures(packageInfo(context.packageName, archive = false))) { "安装包签名不匹配" }
    }

    private fun verifyDigest(file: File, manifest: UpdateManifest): Boolean {
        if (file.length() != manifest.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex() == manifest.sha256.lowercase()
    }

    private fun connection(url: String): HttpURLConnection {
        check(UpdateManifestParser.isHttpsUrl(url)) { "仅支持 HTTPS 更新地址" }
        var current = URL(url)
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
            }
            val status = try { connection.responseCode } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status !in listOf(301, 302, 303, 307, 308)) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            check(!location.isNullOrBlank()) { "更新服务器重定向缺少地址" }
            val next = URL(current, location)
            check(UpdateManifestParser.isHttpsUrl(next.toString())) { "更新服务器重定向到了非 HTTPS 地址" }
            current = next
        }
        error("更新服务器重定向次数过多")
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
