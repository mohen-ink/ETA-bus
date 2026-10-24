package com.mohen.etabus.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.mohen.etabus.BuildConfig
import com.mohen.etabus.model.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LocatedPoint(val point: GeoPoint, val cityName: String, val cityCode: String)

@Singleton
class LocationService @Inject constructor(@ApplicationContext context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()

    suspend fun locate(consented: Boolean): LocatedPoint {
        check(BuildConfig.AMAP_CONFIGURED) { "尚未配置高德地图 Key，暂时无法定位；仍可手动选择城市和搜索线路" }
        check(consented) { "请先阅读并同意隐私说明，再使用定位" }
        check(hasPermission()) { "未获得定位权限，请在系统设置中允许定位，或手动搜索线路" }
        return mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                check(hasPermission()) { "定位权限已被撤回，请重新授权" }
                withTimeoutOrNull(20_000L) { locateOnce() }
                    ?: throw IllegalStateException("定位超时，请检查定位开关和网络后重试")
            }
        }
    }

    private suspend fun locateOnce(): LocatedPoint {
        var client: AMapLocationClient? = null
        try {
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)
            client = AMapLocationClient(context)
            val options = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isOffset = true
                isNeedAddress = true
                isLocationCacheEnable = false
                httpTimeOut = 15_000L
            }
            client.setLocationOption(options)
            val activeClient = client
            return suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean(false)
                activeClient.setLocationListener(AMapLocationListener { location ->
                    if (!delivered.compareAndSet(false, true) || !continuation.isActive) return@AMapLocationListener
                    when {
                        location == null -> continuation.resumeWithException(IllegalStateException("未获得定位结果，请稍后重试"))
                        location.errorCode != 0 -> {
                            val message = when (location.errorCode) {
                                4 -> "定位网络连接失败，请检查网络后重试"
                                7 -> "高德地图 Key 或签名校验失败，请检查应用配置"
                                9 -> "系统定位初始化失败，请检查系统定位开关"
                                12, 13 -> "定位权限不足，请在系统设置中允许定位"
                                14 -> "系统定位服务已关闭，请打开定位开关"
                                18 -> "定位服务启动失败，请稍后重试"
                                else -> "定位失败，请检查定位权限、系统定位开关和网络"
                            }
                            continuation.resumeWithException(IllegalStateException("$message（错误码 ${location.errorCode}）"))
                        }
                        else -> {
                            val point = GeoPoint(location.latitude, location.longitude)
                            if (!point.valid) {
                                continuation.resumeWithException(IllegalStateException("定位返回的坐标无效，请重试"))
                            } else {
                                continuation.resume(LocatedPoint(point, location.city.orEmpty(), location.cityCode.orEmpty()))
                            }
                        }
                    }
                })
                activeClient.startLocation()
            }
        } catch (error: SecurityException) {
            throw IllegalStateException("定位权限不可用，请重新授权后重试", error)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("无法启动高德定位，请检查地图配置和系统定位服务", error)
        } finally {
            client?.let {
                runCatching { it.stopLocation() }
                runCatching { it.onDestroy() }
            }
        }
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
