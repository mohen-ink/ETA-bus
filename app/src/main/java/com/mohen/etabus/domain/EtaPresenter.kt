package com.mohen.etabus.domain

import com.mohen.etabus.model.Arrival
import com.mohen.etabus.model.BusVehicle
import com.mohen.etabus.model.EtaDisplay
import com.mohen.etabus.model.EtaKind
import com.mohen.etabus.model.RouteDetail
import com.mohen.etabus.model.formatDistance
import com.mohen.etabus.model.normalizeRoute
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

object EtaPresenter {
    private val beijing = ZoneId.of("Asia/Shanghai")
    private val refreshFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val clockPattern = Regex("^(\\d{1,2}):(\\d{2})$")
    private val timeNumber = Regex("\\d+(?:\\.\\d+)?")
    private data class ServiceWindow(val begin: String, val end: String, val first: Int, val last: Int) {
        fun contains(minute: Int): Boolean = when {
            first == last -> true
            first < last -> minute in first..last
            else -> minute >= first || minute <= last
        }
    }

    fun present(
        arrival: Arrival?,
        detail: RouteDetail? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): EtaDisplay {
        val route = detail?.takeIf {
            arrival == null || (normalizeRoute(it.routeName) == normalizeRoute(arrival.routeName) && it.direction == arrival.direction)
        }
        val windows = arrival?.firstLast.orEmpty().mapNotNull { window(it.beginTime, it.endTime) }
            .ifEmpty { listOfNotNull(route?.let { window(it.beginTime, it.endTime) }) }
        val serviceText = windows.joinToString(" / ") { "首${it.begin} 末${it.end}" }
        val tips = arrival?.tips.orEmpty().trim()
        when {
            tips.contains("非运营时间") -> return EtaDisplay("非运营时间", serviceText, EtaKind.ALERT)
            tips.contains("已过末班") || tips.contains("末班已过") || tips.contains("末班车已发") ->
                return EtaDisplay("已过末班", serviceText, EtaKind.ALERT)
            tips.contains("等待发车") -> {
                val plan = arrival?.planTime.orEmpty().ifBlank { route?.planTime.orEmpty() }
                    .takeIf { it != "-1" && !it.contains("2500000") }.orEmpty()
                return EtaDisplay("等待发车", if (plan.isNotBlank()) "计划发车 $plan" else "", EtaKind.IDLE)
            }
        }
        if (arrival != null) {
            if (arrival.stops > 0) {
                val minutes = safeTimeTips(arrival.timeTips).ifBlank {
                    arrival.validMinutes?.let {
                        if (it < 1) "小于1分钟" else "${ceil(it).toInt()}分钟"
                    }.orEmpty()
                }
                val distance = arrival.validDistance?.let { formatDistance(it.toDouble()) }.orEmpty()
                return EtaDisplay("${arrival.stops}站", listOf(minutes, distance).filter { it.isNotBlank() }.joinToString(" · "), EtaKind.LIVE, true)
            }
            if (arrival.stops == 0 && tips.contains("已到站")) {
                return EtaDisplay("已到站", "", EtaKind.ARRIVING, true)
            }
            if (arrival.stops == 0 && tips.contains("即将到站")) {
                return EtaDisplay("即将到站", safeTimeTips(arrival.timeTips), EtaKind.ARRIVING, true)
            }
        }
        if (windows.isNotEmpty() && route?.buses.isNullOrEmpty()) {
            val clock = Instant.ofEpochMilli(nowMillis).atZone(beijing)
            val minute = clock.hour * 60 + clock.minute
            if (windows.none { it.contains(minute) }) {
                val afterLast = windows.all { it.first < it.last && minute > it.last }
                return EtaDisplay(if (afterLast) "已过末班" else "非运营时间", serviceText, EtaKind.ALERT)
            }
        }
        if (arrival == null && route != null && route.buses.isEmpty()) {
            return EtaDisplay("暂无来车", updated(route.updatedAt), EtaKind.IDLE)
        }
        return EtaDisplay("暂无数据", arrival?.let { updated(it.updatedAt) } ?: "等待实时数据", EtaKind.IDLE)
    }

    fun secondBus(detail: RouteDetail?, waitingIndex: Int, first: Arrival?): EtaDisplay? {
        if (detail == null || waitingIndex !in detail.stations.indices) return null
        val ahead = detail.buses.filter { it.index in 0..waitingIndex }.sortedBy { stopsTo(it, waitingIndex) }
        val firstNumber = first?.busNumber?.trim().orEmpty()
        val firstPosition = ahead.indexOfFirst { firstNumber.isNotEmpty() && it.busNumber.trim() == firstNumber }
        val bus = ahead.getOrNull(if (firstPosition >= 0) firstPosition + 1 else 1)
            ?: return EtaDisplay("暂无来车", "", EtaKind.IDLE)
        val stops = stopsTo(bus, waitingIndex)
        if (stops <= 0) return EtaDisplay("已到站", "", EtaKind.ARRIVING, true)
        val meters = distanceTo(detail, bus, waitingIndex)
        val pace = first?.takeIf { it.stops > 0 }?.let { a ->
            a.validMinutes?.let { m -> a.validDistance?.takeIf { it > 0 }?.let { m / it } }
        }?.takeIf { it in 0.001..0.01 } ?: DEFAULT_MINUTES_PER_METER
        val minutes = meters?.let { "约${ceil(it * pace).toInt().coerceAtLeast(1)}分钟" }.orEmpty()
        val distance = meters?.let(::formatDistance).orEmpty()
        return EtaDisplay("${stops}站", listOf(minutes, distance).filter { it.isNotBlank() }.joinToString(" · "), EtaKind.LIVE, true)
    }

    private const val DEFAULT_MINUTES_PER_METER = 0.003

    private fun stopsTo(bus: BusVehicle, waitingIndex: Int): Int = waitingIndex - bus.index + if (bus.approaching) 1 else 0

    private fun distanceTo(detail: RouteDetail, bus: BusVehicle, waitingIndex: Int): Double? {
        val points = detail.stationPoints
        var total = if (bus.approaching) {
            bus.fittedDistance.takeIf { it > 0 && it.isFinite() }
                ?: bus.point?.let { p -> points.getOrNull(bus.index)?.let(p::distanceTo) } ?: return null
        } else 0.0
        for (i in bus.index until waitingIndex) {
            val a = points.getOrNull(i) ?: return null
            val b = points.getOrNull(i + 1) ?: return null
            total += a.distanceTo(b)
        }
        return total
    }

    private fun safeTimeTips(value: String): String {
        val text = value.trim()
        if (text.isEmpty() || text.contains("-1") || text.contains("暂无") || text.contains("2500000")) return ""
        val numbers = timeNumber.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        return text.takeIf { numbers.isNotEmpty() && numbers.all { it.isFinite() && it >= 0 && it < 10000 } && text.contains("分钟") }.orEmpty()
    }

    private fun window(begin: String, end: String): ServiceWindow? {
        val first = minute(begin, false) ?: return null
        val last = minute(end, true) ?: return null
        return ServiceWindow("%02d:%02d".format(java.util.Locale.US, first / 60, first % 60),
            "%02d:%02d".format(java.util.Locale.US, last / 60, last % 60), first, last)
    }

    private fun minute(text: String, allowMidnightEnd: Boolean): Int? {
        val match = clockPattern.matchEntire(text.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (allowMidnightEnd && hour == 24 && minute == 0) return 1440
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun updated(millis: Long): String = if (millis > 0) {
        "更新于 ${Instant.ofEpochMilli(millis).atZone(beijing).format(refreshFormat)}"
    } else "等待实时数据"
}