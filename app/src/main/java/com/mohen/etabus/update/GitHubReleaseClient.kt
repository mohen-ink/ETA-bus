package com.mohen.etabus.update

import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

data class AppVersion(val major: BigInteger, val minor: BigInteger, val patch: BigInteger) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(value.trim()) ?: return null
            return AppVersion(match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(), match.groupValues[3].toBigInteger())
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)
data class GitHubRelease(
    val tag: String, val version: AppVersion, val notes: String, val publishedAt: String,
    val pageUrl: String, val asset: ReleaseAsset?
)

@Singleton
class GitHubReleaseClient @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun latest(supportedAbis: List<String>): GitHubRelease {
        val request = Request.Builder().url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ETA-bus-Android")
            .build()
        val json = execute(request) { response ->
            checkStatus(response)
            val body = response.body ?: throw IOException("GitHub没有返回版本信息")
            val source = body.source()
            source.request(MAX_METADATA_BYTES + 1)
            if (source.buffer.size > MAX_METADATA_BYTES) throw IOException("GitHub版本信息过大")
            source.readUtf8()
        }
        try {
            val release = JSONObject(json)
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) throw IOException("没有可用的正式版本")
            val tag = release.getString("tag_name")
            val version = AppVersion.parse(tag) ?: throw IOException("GitHub版本号格式无法识别：$tag")
            val rawAssets = release.getJSONArray("assets")
            val assets = List(rawAssets.length()) { rawAssets.getJSONObject(it) }
            val candidate = supportedAbis.firstNotNullOfOrNull { abi ->
                assets.firstOrNull { it.optString("name").endsWith("-$abi.apk", ignoreCase = true) }
            } ?: assets.firstOrNull { it.optString("name").endsWith("-universal.apk", ignoreCase = true) }
            val asset = candidate?.let {
                val url = it.getString("browser_download_url")
                trustedDownloadUrl(url.toHttpUrlOrNull() ?: throw IOException("安装包下载地址无效"), initial = true)
                val size = it.getLong("size")
                if (size <= 0) throw IOException("安装包大小无效")
                val rawDigest = if (it.isNull("digest")) "" else it.optString("digest")
                val digest = rawDigest.takeIf(String::isNotBlank)?.let { value ->
                    if (!value.startsWith("sha256:") || !Regex("[0-9a-fA-F]{64}").matches(value.removePrefix("sha256:"))) {
                        throw IOException("安装包校验信息无法识别")
                    }
                    value.removePrefix("sha256:")
                }
                ReleaseAsset(it.getString("name"), url, size, digest)
            }
            return GitHubRelease(tag, version, release.optString("body").takeUnless { it == "null" }.orEmpty(),
                release.optString("published_at"), "$REPOSITORY_URL/releases/tag/$tag", asset)
        } catch (error: JSONException) {
            throw IOException("GitHub没有返回有效的版本信息", error)
        }
    }

    suspend fun download(release: GitHubRelease, cacheDir: File, progress: (Long) -> Unit): File {
        val asset = release.asset ?: throw IOException("该版本没有适合本机的安装包")
        var url = asset.url.toHttpUrlOrNull() ?: throw IOException("安装包下载地址无效")
        trustedDownloadUrl(url, initial = true)
        val job = currentCoroutineContext()[Job]
        repeat(6) {
            job?.ensureActive()
            val request = Request.Builder().url(url).header("User-Agent", "ETA-bus-Android")
                .header("Accept", "application/octet-stream").build()
            val result = execute(request, discard = { it.file?.delete() }) { response ->
                job?.ensureActive()
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: throw IOException("GitHub下载跳转缺少地址")
                    val next = response.request.url.resolve(location) ?: throw IOException("GitHub下载跳转地址无效")
                    trustedDownloadUrl(next, initial = false)
                    DownloadResult(redirect = next)
                } else {
                    checkStatus(response)
                    DownloadResult(file = save(response, cacheDir, asset, job, progress))
                }
            }
            result.file?.let { return it }
            url = result.redirect ?: throw IOException("未获得安装包下载地址")
        }
        throw IOException("GitHub下载跳转次数过多，请打开Release页面下载")
    }

    private fun save(response: Response, cacheDir: File, asset: ReleaseAsset, job: Job?, progress: (Long) -> Unit): File {
        val body = response.body ?: throw IOException("GitHub没有返回安装包")
        if (body.contentLength() >= 0 && body.contentLength() != asset.size) throw IOException("安装包大小与发布信息不一致")
        val directory = File(cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新缓存目录")
        val partial = File.createTempFile("release-", ".part", directory)
        val target = File(directory, partial.name.removeSuffix(".part") + ".apk")
        var complete = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        job?.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > asset.size) throw IOException("安装包大小超过发布信息")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastProgress >= 100_000_000L || received == asset.size) {
                            progress(received)
                            lastProgress = now
                        }
                    }
                }
            }
            job?.ensureActive()
            if (received != asset.size) throw IOException("安装包下载不完整，请重试")
            val actual = digest.digest().toHex()
            if (asset.sha256 != null && !actual.equals(asset.sha256, ignoreCase = true)) throw IOException("安装包SHA-256校验失败，请重新下载")
            if (!partial.renameTo(target)) throw IOException("无法保存安装包")
            complete = true
            return target
        } finally {
            partial.delete()
            if (!complete) target.delete()
        }
    }

    private fun trustedDownloadUrl(url: HttpUrl, initial: Boolean) {
        val validHost = if (initial) url.host == "github.com" && url.encodedPath.startsWith("/mohen-ink/ETA-bus/releases/download/")
        else url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || !validHost) {
            throw IOException("安装包下载地址不属于可信的GitHub发布源")
        }
    }

    private fun checkStatus(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 404 -> throw IOException("未找到可用的GitHub版本或安装包")
            response.code == 403 || response.code == 429 -> throw IOException("GitHub拒绝访问或访问频率受限，请稍后重试")
            else -> throw IOException("GitHub请求失败（HTTP ${response.code}）")
        }
    }

    private suspend fun <T> execute(request: Request, discard: (T) -> Unit = {}, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(consume)
                        continuation.resume(result, onCancellation = { _, value, _ -> discard(value) })
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private data class DownloadResult(val redirect: HttpUrl? = null, val file: File? = null)

    companion object {
        const val REPOSITORY_URL = "https://github.com/mohen-ink/ETA-bus"
        const val RELEASES_URL = "$REPOSITORY_URL/releases/latest"
        private const val API_URL = "https://api.github.com/repos/mohen-ink/ETA-bus/releases/latest"
        private const val MAX_METADATA_BYTES = 1024L * 1024L
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }