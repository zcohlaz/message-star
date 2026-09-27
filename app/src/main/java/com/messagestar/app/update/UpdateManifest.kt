package com.messagestar.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class UpdateManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String
)

object UpdateManifestParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_APK_BYTES = 150L * 1024 * 1024

    fun parse(body: String, expectedPackage: String): UpdateManifest {
        val manifest = json.decodeFromString<UpdateManifest>(body)
        require(manifest.packageName == expectedPackage) { "更新包名不匹配" }
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank()) { "版本信息无效" }
        require(manifest.versionName.length <= 80 && manifest.notes.length <= 4000) { "更新说明过长" }
        require(manifest.sizeBytes in 1..MAX_APK_BYTES) { "安装包大小无效" }
        require(manifest.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "SHA-256 校验值无效" }
        require(isHttpsUrl(manifest.apkUrl)) { "安装包地址必须使用 HTTPS" }
        return manifest
    }

    fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
