package com.mohen.etabus.network

import com.mohen.etabus.model.Arrival
import com.mohen.etabus.model.CityConfig
import com.mohen.etabus.model.GeoPoint
import com.mohen.etabus.model.Platform
import com.mohen.etabus.model.RouteDetail
import com.mohen.etabus.model.RouteSelection
import com.mohen.etabus.model.RouteSummary
import com.mohen.etabus.model.normalizeRoute
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Singleton
class BusApi @Inject constructor() {
    suspend fun nearby(
        city: CityConfig,
        center: GeoPoint,
        radius: Int,
        keyword: String = "",
        owner: String = "default",
        priority: Int = 0
    ): List<Platform> {
        if (!center.valid) throw BusApiException("缺少有效查询坐标，请先定位或选择地图中心")
        if (radius <= 0) throw BusApiException("查询半径必须大于零")
        return request(city, 204, Commands.nearby(city, center, radius, keyword), owner, priority) {
            Commands.platforms(it).items.distinctBy { platform -> platform.key }
                .filter { platform -> center.distanceTo(platform.point) <= radius }
                .sortedBy { platform -> center.distanceTo(platform.point) }
        }
    }

    suspend fun station(
        city: CityConfig,
        platform: Platform,
        owner: String = "default",
        priority: Int = 0
    ): List<Arrival> {
        if (!platform.point.valid) throw BusApiException("站台坐标无效，请重新选择站台")
        return request(city, 149, Commands.station(platform), owner, priority) { Commands.arrivals(it).items }
    }

    suspend fun route(
        city: CityConfig,
        selection: RouteSelection,
        owner: String = "default",
        priority: Int = 0
    ): RouteDetail {
        if (normalizeRoute(selection.routeName).isBlank() || selection.direction.isBlank()) {
            throw BusApiException("请选择有效线路和方向")
        }
        return request(city, 145, Commands.route(selection), owner, priority) { plain ->
            Commands.routes(plain).items.firstOrNull {
                it.routeName == normalizeRoute(selection.routeName) && it.direction == selection.direction
            } ?: throw BusApiException("暂未找到该线路方向的详情，请稍后重试")
        }
    }

    suspend fun search(
        city: CityConfig,
        keyword: String,
        center: GeoPoint?,
        byStation: Boolean = false,
        owner: String = "default",
        priority: Int = 0
    ): List<RouteSummary> {
        if (keyword.isBlank()) return emptyList()
        if (center != null && !center.valid) throw BusApiException("查询坐标无效，请重新定位")
        val command = if (byStation) 30 else 25
        return request(city, command, Commands.search(city, keyword, center, byStation), owner, priority) {
            Commands.summaries(it).items.sortedBy { summary ->
                if (summary.routeName == normalizeRoute(keyword)) 0 else 1
            }
        }
    }

    fun cancelOwner(owner: String) { queue.cancelOwner(owner) }

    private suspend fun <T : Any> request(
        city: CityConfig,
        command: Int,
        content: ByteArray,
        owner: String,
        priority: Int,
        parse: (ByteArray) -> T
    ): T {
        val key = "${city.code}|${city.endpoint}|$command|${Base64.getEncoder().encodeToString(content)}"
        return queue.execute(key, owner, priority) {
            try {
                parse(payload(city, command, content))
            } catch (error: CancellationException) {
                throw error
            } catch (error: BusApiException) {
                throw error
            } catch (error: IOException) {
                throw BusApiException("网络连接失败，请检查网络后重试", error)
            } catch (error: Exception) {
                throw BusApiException("公交查询失败，请稍后重试", error)
            }
        }
    }

    private suspend fun payload(city: CityConfig, command: Int, content: ByteArray): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val frame = binary { int(command); sized(content) }
            val builder = Request.Builder().url(city.endpoint)
                .post(frame.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            HeaderBuilder.build(city, content).forEach { (name, value) -> builder.header(name, value) }
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val plain = response.use {
                            if (!it.isSuccessful) throw BusApiException("公交服务连接异常（HTTP ${it.code}），请稍后重试")
                            val body = it.body ?: throw ProtocolException("公交服务未返回响应内容")
                            if (body.contentLength() > ResponseDecoder.MAX_BYTES) throw ProtocolException("公交响应过大，请稍后重试")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > ResponseDecoder.MAX_BYTES) throw ProtocolException("公交响应过大，请稍后重试")
                                    output.write(buffer, 0, count)
                                }
                            }
                            ResponseDecoder.decode(command, output.toByteArray()) { name -> it.header(name) }
                        }
                        if (continuation.isActive) continuation.resume(plain)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private companion object {
        val queue = RequestQueue()
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}