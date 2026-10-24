package com.mohen.etabus.network

import com.mohen.etabus.model.Arrival
import com.mohen.etabus.model.BusVehicle
import com.mohen.etabus.model.CityConfig
import com.mohen.etabus.model.FirstLastClass
import com.mohen.etabus.model.GeoPoint
import com.mohen.etabus.model.Platform
import com.mohen.etabus.model.RouteDetail
import com.mohen.etabus.model.RouteSelection
import com.mohen.etabus.model.RouteSummary
import com.mohen.etabus.model.normalizeRoute
import kotlinx.coroutines.CancellationException

internal data class ResultVector<T>(val items: List<T>, val unknownTail: ByteArray)

internal object Commands {
    fun nearby(city: CityConfig, center: GeoPoint, radius: Int, keyword: String): ByteArray = binary {
        utf(""); utf(""); utf(center.lat.toString()); utf(center.lng.toString()); utf(radius.toString())
        utf("公交站"); utf(keyword); utf("")
        repeat(5) { int(0) }
        int(30); int(0); utf(city.code)
    }

    fun station(platform: Platform): ByteArray = binary {
        utf(platform.name); utf(platform.point.lat.toString()); int(99); int(0)
        utf(platform.point.lng.toString()); utf(""); utf(""); utf(""); int(0); int(0)
    }

    fun route(selection: RouteSelection): ByteArray = binary {
        val station = selection.platform?.name.orEmpty()
        val item = binary {
            utf(normalizeRoute(selection.routeName)); utf(selection.direction); bool(true)
            utf(station); utf(if (station.isNotEmpty() && selection.stationIndex >= 0) selection.stationIndex.toString() else "")
            int(0)
        }
        int(1); sized(item); utf(station); bool(true)
    }

    fun search(city: CityConfig, keyword: String, center: GeoPoint?, byStation: Boolean): ByteArray = binary {
        utf(city.name); utf(if (byStation) "" else normalizeRoute(keyword)); utf("")
        utf(if (byStation) keyword else ""); utf(""); bool(true)
        utf(center?.lat?.toString().orEmpty()); utf(center?.lng?.toString().orEmpty())
    }

    fun platforms(plain: ByteArray): ResultVector<Platform> = vector(plain) { reader ->
        val name = reader.utf()
        reader.utf()
        val routes = reader.int()
        reader.int()
        val point = point(reader.utf(), reader.utf())
            ?: throw ProtocolException("站台坐标无效，请重新查询")
        Platform(name, point, routes)
    }

    fun arrivals(plain: ByteArray): ResultVector<Arrival> = vector(plain) { reader ->
        val route = normalizeRoute(reader.utf())
        val direction = reader.utf()
        val station = reader.utf()
        val index = reader.utf().toIntOrNull() ?: -1
        val from = reader.utf()
        val to = reader.utf()
        val stops = reader.int()
        val minutes = reader.double()
        val distance = reader.int()
        val tips = reader.utf()
        val timeTips = reader.utf()
        val distanceTips = reader.utf()
        val busStation = reader.utf()
        val number = reader.utf()
        val point = point(reader.utf(), reader.utf())
        val planTime = reader.utf()
        val tail = reader.bytesFrom(reader.position)
        var exception = ""
        var color = ""
        val firstLast = mutableListOf<FirstLastClass>()
        if (reader.remaining > 0) {
            try {
                reader.utf(); reader.utf(); reader.int()
                repeat(5) { reader.utf() }
                reader.int()
                exception = reader.utf()
                repeat(4) { reader.utf() }
                reader.int(); reader.int()
                repeat(reader.count(99)) {
                    val raw = reader.take(reader.int())
                    val entry = BinaryReader(raw)
                    var begin = ""
                    var end = ""
                    try {
                        val first = entry.utf()
                        val last = entry.utf()
                        begin = first
                        end = last
                    } catch (_: java.io.IOException) {
                        // The complete bounded item remains available in raw.
                    }
                    firstLast += FirstLastClass(begin, end, raw)
                }
                reader.utf()
                color = reader.utf()
                reader.utf(); reader.utf()
            } catch (error: CancellationException) {
                throw error
            } catch (_: java.io.IOException) {
                // Do not guess the layout after an unknown extension boundary.
            }
        }
        Arrival(route, direction, station, index, from, to, stops, minutes, distance,
            tips, timeTips, distanceTips, busStation, number, point, planTime,
            exception, color, firstLast, tail)
    }

    fun routes(plain: ByteArray): ResultVector<RouteDetail> = vector(plain) { reader ->
        val name = normalizeRoute(reader.utf())
        val direction = reader.utf()
        val index = reader.utf().toIntOrNull() ?: -1
        val stationName = reader.utf()
        val beginTime = reader.utf()
        val endTime = reader.utf()
        val planTime = reader.utf()
        val busData = reader.take(reader.int())
        val buses = vector(busData, ::vehicle).items
        val station = point(reader.utf(), reader.utf())
        val stationText = reader.utf()
        val stations = if (stationText.isEmpty()) emptyList() else stationText.split('-')
        val comments = reader.utf()
        val tailOffset = reader.position
        var latitudes = emptyList<String>()
        var longitudes = emptyList<String>()
        var fitted = ""
        if (reader.remaining > 0) {
            try {
                val lat = reader.utf().split('-')
                val lng = reader.utf().split('-')
                latitudes = lat
                longitudes = lng
                fitted = reader.utf()
            } catch (_: java.io.IOException) {
                // Keep original indices even if the optional coordinate suffix is incomplete.
            }
        }
        val points = stations.indices.map { stationIndex ->
            point(latitudes.getOrNull(stationIndex).orEmpty(), longitudes.getOrNull(stationIndex).orEmpty())
        }
        RouteDetail(name, direction, index, stationName, beginTime, endTime, planTime,
            buses, station, stations, comments, points, fitted, reader.bytesFrom(tailOffset))
    }

    fun summaries(plain: ByteArray): ResultVector<RouteSummary> = vector(plain) { reader ->
        val city = reader.utf()
        val name = normalizeRoute(reader.utf())
        val begin = reader.utf()
        val end = reader.utf()
        val direction = reader.utf()
        val from = reader.utf()
        val to = reader.utf()
        val comments = reader.utf()
        val station = reader.utf()
        val index = reader.utf().toIntOrNull() ?: -1
        RouteSummary(city, name, begin, end, direction, from, to, comments, station, index, reader.rest())
    }

    private fun vehicle(reader: BinaryReader): BusVehicle {
        val name = reader.utf()
        val status = reader.utf()
        val index = reader.int()
        val stationPoint = point(reader.double(), reader.double())
        val point = point(reader.double(), reader.double())
        val number = reader.utf()
        val tailOffset = reader.position
        var crowded = ""
        var distance = 0.0
        var fittedIndex = 0
        var angle = 0.0
        var time = 0L
        if (reader.remaining > 0) {
            try {
                crowded = reader.utf()
                distance = reader.double()
                fittedIndex = reader.int()
                angle = reader.double()
                time = reader.long()
                repeat(5) { reader.utf() }
            } catch (_: java.io.IOException) {
                // Preserve any newer or shorter vehicle extension without inventing fields.
            }
        }
        return BusVehicle(name, status, index, stationPoint, point, number, crowded,
            distance, fittedIndex, angle, time, reader.bytesFrom(tailOffset))
    }

    private fun point(lat: String, lng: String): GeoPoint? = point(lat.toDoubleOrNull(), lng.toDoubleOrNull())
    private fun point(lat: Double?, lng: Double?): GeoPoint? =
        if (lat == null || lng == null) null else GeoPoint(lat, lng).takeIf { it.valid }

    private fun <T> vector(plain: ByteArray, parse: (BinaryReader) -> T): ResultVector<T> {
        try {
            val reader = BinaryReader(plain)
            val items = List(reader.count()) { parse(reader.item()) }
            return ResultVector(items, reader.rest())
        } catch (error: CancellationException) {
            throw error
        } catch (error: BusApiException) {
            throw error
        } catch (error: Exception) {
            throw ProtocolException("公交数据解析失败，请稍后重试", error)
        }
    }
}