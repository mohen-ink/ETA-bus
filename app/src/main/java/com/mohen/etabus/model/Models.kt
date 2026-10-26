package com.mohen.etabus.model

import java.io.Serializable
import kotlin.math.*

data class CityConfig(val code: String, val name: String, val host: String, val port: String, val dir: String) : Serializable {
    val displayName: String get() = name.removeSuffix("市")
    val endpoint: String get() = "http://$host:$port/$dir/servlet/MyGoServer.HttpPool.HttpHandlerServlet"
}

data class GeoPoint(val lat: Double, val lng: Double) : Serializable {
    val valid: Boolean get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
    fun distanceTo(other: GeoPoint): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(other.lat)
        val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(other.lng - lng) / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

data class Platform(val name: String, val point: GeoPoint, val routeCount: Int = 0) : Serializable {
    val key: String get() = "$name|${point.lat}|${point.lng}"
}

data class StationGroup(val name: String, val platforms: List<Platform>, val distanceMeters: Double? = null) : Serializable {
    val key: String get() = platforms.firstOrNull()?.key ?: name
}

data class FirstLastClass(val beginTime: String = "", val endTime: String = "", val raw: ByteArray = byteArrayOf()) : Serializable

data class Arrival(
    val routeName: String, val direction: String, val stationName: String, val stationIndex: Int,
    val fromStation: String, val toStation: String, val stops: Int, val minutes: Double, val distanceMeters: Int,
    val tips: String, val timeTips: String, val distanceTips: String, val busStationName: String,
    val busNumber: String, val point: GeoPoint?, val planTime: String = "",
    val exceptionMsg: String = "", val routeColor: String = "", val firstLast: List<FirstLastClass> = emptyList(),
    val unknownTail: ByteArray = byteArrayOf(), val updatedAt: Long = System.currentTimeMillis()
) : Serializable {
    val key: String get() = "$routeName|$direction|$stationIndex"
    val validMinutes: Double? get() = minutes.takeIf { it.isFinite() && it >= 0 && it < 10000 }
    val validDistance: Int? get() = distanceMeters.takeIf { it >= 0 }
}

data class BusVehicle(
    val stationName: String, val statusType: String, val index: Int, val stationPoint: GeoPoint?,
    val point: GeoPoint?, val busNumber: String, val crowdedStatus: String = "",
    val fittedDistance: Double = 0.0, val fittedIndex: Int = 0, val angle: Double = 0.0,
    val recordedAt: Long = 0, val unknownTail: ByteArray = byteArrayOf()
) : Serializable {
    val approaching: Boolean get() = statusType == "2"
}

data class RouteDetail(
    val routeName: String, val direction: String, val stationIndex: Int, val stationName: String,
    val beginTime: String, val endTime: String, val planTime: String, val buses: List<BusVehicle>,
    val stationPoint: GeoPoint?, val stations: List<String>, val comments: String,
    val stationPoints: List<GeoPoint?> = emptyList(), val fittedPoints: String = "",
    val unknownTail: ByteArray = byteArrayOf(), val updatedAt: Long = System.currentTimeMillis()
) : Serializable

data class RouteSummary(
    val cityName: String, val routeName: String, val beginTime: String, val endTime: String,
    val direction: String, val fromStation: String, val toStation: String, val comments: String,
    val stationName: String, val stationIndex: Int, val unknownTail: ByteArray = byteArrayOf()
) : Serializable

data class RouteSelection(
    val routeName: String, val direction: String, val fromStation: String = "", val toStation: String = "",
    val platform: Platform? = null, val stationIndex: Int = -1
) : Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(
    val cityCode: String = "0719", val radius: Int = 1000, val refreshSeconds: Int = 30,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val privacyAccepted: Boolean = false, val searchHistory: List<String> = emptyList(),
    val recentCities: List<String> = emptyList()
)

enum class SavedKind { ROUTE, STATION }
data class SavedItem(
    val id: String, val cityCode: String, val kind: SavedKind, val station: StationGroup? = null,
    val route: RouteSelection? = null, val sortOrder: Long = System.currentTimeMillis(),
    val viewedAt: Long = System.currentTimeMillis()
) : Serializable

enum class EtaKind { LIVE, ARRIVING, ALERT, IDLE }
data class EtaDisplay(val title: String, val subtitle: String, val kind: EtaKind, val live: Boolean = false)

fun normalizeRoute(value: String): String = value.replace("·", "").trim()
fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.toInt()}米" else String.format(java.util.Locale.CHINA, "%.1f公里", meters / 1000)