package com.mohen.etabus.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.mohen.etabus.BuildConfig
import com.mohen.etabus.model.BusVehicle
import com.mohen.etabus.model.GeoPoint
import com.mohen.etabus.model.Platform

@Composable
fun BusMap(
    modifier: Modifier = Modifier,
    consented: Boolean,
    center: GeoPoint?,
    platforms: List<Platform> = emptyList(),
    selected: Platform? = null,
    userPoint: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    buses: List<BusVehicle> = emptyList(),
    onPlatformClick: (Platform) -> Unit = {},
    onCenterChanged: (GeoPoint) -> Unit = {}
) {
    val initialPoint = selected?.point?.takeIf { it.valid }
        ?: center?.takeIf { it.valid }
        ?: userPoint?.takeIf { it.valid }
        ?: platforms.firstOrNull { it.point.valid }?.point
        ?: routePoints.firstOrNull { it.valid }
        ?: buses.firstNotNullOfOrNull { it.point?.takeIf(GeoPoint::valid) }
    val unavailable = when {
        !BuildConfig.AMAP_CONFIGURED -> "地图暂不可用，仍可搜索线路和查看到站。"
        !consented -> "同意隐私说明后即可查看地图。"
        initialPoint == null -> "请先定位或选择站点。"
        else -> null
    }
    if (unavailable != null) {
        MapPlaceholder(modifier, unavailable)
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val creation = remember(context, lifecycleOwner) { runCatching { MapSession.create(context) } }
    val session = creation.getOrNull()
    if (session == null) {
        MapPlaceholder(modifier, "地图加载失败，请稍后重新进入。")
        return
    }
    var failure by remember(session) { mutableStateOf<String?>(null) }
    DisposableEffect(session, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> session.resume()
                    Lifecycle.Event.ON_PAUSE -> session.pause()
                    Lifecycle.Event.ON_DESTROY -> session.destroy()
                    else -> Unit
                }
            }.onFailure {
                session.destroy()
                failure = "地图加载失败，请稍后重新进入。"
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            session.destroy()
        }
    }
    val message = failure
    if (message != null) {
        MapPlaceholder(modifier, message)
    } else {
        AndroidView(
            factory = { session.view },
            modifier = modifier.onSizeChanged { size ->
                runCatching { session.resize(size.width, size.height) }.onFailure {
                    session.destroy()
                    failure = "地图加载失败，请稍后重新进入。"
                }
            },
            update = {
                runCatching {
                    session.update(center, platforms, selected, userPoint, routePoints, buses, onPlatformClick, onCenterChanged)
                }.onFailure {
                    session.destroy()
                    failure = "地图加载失败，请稍后重新进入。"
                }
            }
        )
    }
}

@Composable
private fun MapPlaceholder(modifier: Modifier, message: String) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

private class MapSession private constructor(val view: MapView, private val density: Float) {
    private val map = view.map
    private var destroyed = false
    private var resumed = false
    private var positioned = false
    private var lastCenter: GeoPoint? = null
    private var lastSelected: String? = null
    private var currentSelected: Platform? = null
    private var lastScene: Scene? = null
    private var lastReportedCenter: GeoPoint? = null
    private var platformClick: (Platform) -> Unit = {}
    private var centerChanged: (GeoPoint) -> Unit = {}
    private val icons = mutableMapOf<String, BitmapDescriptor>()

    init {
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isMyLocationButtonEnabled = false
            isCompassEnabled = true
        }
        map.setOnMarkerClickListener { marker ->
            val platform = marker.`object` as? Platform
            if (platform == null) {
                false
            } else {
                focus(platform.point)
                marker.showInfoWindow()
                platformClick(platform)
                true
            }
        }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) = Unit
            override fun onCameraChangeFinish(position: CameraPosition?) {
                if (destroyed || position == null) return
                val point = GeoPoint(position.target.latitude, position.target.longitude)
                if (point.valid && (lastReportedCenter?.distanceTo(point) ?: Double.MAX_VALUE) >= 0.5) {
                    lastReportedCenter = point
                    centerChanged(point)
                }
            }
        })
    }

    fun update(
        center: GeoPoint?, platforms: List<Platform>, selected: Platform?, userPoint: GeoPoint?,
        routePoints: List<GeoPoint>, buses: List<BusVehicle>,
        onPlatformClick: (Platform) -> Unit, onCenterChanged: (GeoPoint) -> Unit
    ) {
        if (destroyed) return
        platformClick = onPlatformClick
        centerChanged = onCenterChanged
        currentSelected = selected?.takeIf { it.point.valid }
        val scene = Scene(platforms.toList(), currentSelected, userPoint?.takeIf { it.valid }, routePoints.toList(), buses.toList())
        if (scene != lastScene) {
            render(scene)
            lastScene = scene
        }
        val centerChangedExternally = center != lastCenter
        val selectionChanged = currentSelected?.key != lastSelected
        val target = when {
            selectionChanged && currentSelected != null -> currentSelected?.point
            centerChangedExternally && center?.valid == true -> center
            !positioned -> currentSelected?.point ?: center?.takeIf { it.valid } ?: scene.userPoint
                ?: platforms.firstOrNull { it.point.valid }?.point ?: routePoints.firstOrNull { it.valid }
                ?: buses.firstNotNullOfOrNull { it.point?.takeIf(GeoPoint::valid) }
            else -> null
        }
        lastCenter = center
        lastSelected = currentSelected?.key
        if (target != null) focus(target)
    }

    private fun render(scene: Scene) {
        map.clear()
        val route = scene.routePoints.filter(GeoPoint::valid)
        if (route.size >= 2) {
            map.addPolyline(PolylineOptions().addAll(route.map(GeoPoint::latLng)).width(8f * density).color(Color.rgb(37, 99, 235)).zIndex(1f))
        }
        if (scene.userPoint != null && scene.selected != null) {
            map.addPolyline(
                PolylineOptions().add(scene.userPoint.latLng(), scene.selected.point.latLng())
                    .width(3f * density).color(Color.rgb(100, 116, 139)).setDottedLine(true).zIndex(2f)
            )
        }
        (scene.platforms + listOfNotNull(scene.selected)).filter { it.point.valid }.distinctBy(Platform::key).forEach { platform ->
            val isSelected = platform.key == scene.selected?.key
            val marker = map.addMarker(
                MarkerOptions().position(platform.point.latLng()).title(platform.name)
                    .icon(icon(if (isSelected) "selected" else "station")).anchor(0.5f, 0.5f).zIndex(if (isSelected) 6f else 3f)
            )
            marker.`object` = platform
            if (isSelected) marker.showInfoWindow()
        }
        scene.userPoint?.let { point ->
            map.addMarker(MarkerOptions().position(point.latLng()).title("我的位置").icon(icon("user")).anchor(0.5f, 0.5f).zIndex(5f))
        }
        scene.buses.forEach { bus ->
            val point = bus.point?.takeIf { it.valid } ?: return@forEach
            map.addMarker(
                MarkerOptions().position(point.latLng()).title(bus.busNumber.ifBlank { "公交车辆" })
                    .snippet(if (bus.approaching) "开往${bus.stationName}" else "到达${bus.stationName}")
                    .icon(icon("bus")).anchor(0.5f, 0.5f).zIndex(7f)
            )
        }
    }

    private fun icon(kind: String): BitmapDescriptor = icons.getOrPut(kind) {
        val size = (36 * density).toInt().coerceAtLeast(36)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val middle = size / 2f
        val radius = size * if (kind == "user") 0.24f else 0.43f
        paint.color = Color.WHITE
        canvas.drawCircle(middle, middle, radius + 2 * density, paint)
        paint.color = when (kind) {
            "selected" -> Color.rgb(31, 58, 138)
            "station" -> Color.rgb(100, 116, 139)
            "bus" -> Color.rgb(245, 158, 11)
            "congested" -> Color.rgb(220, 90, 60)
            else -> Color.rgb(37, 99, 235)
        }
        canvas.drawCircle(middle, middle, radius, paint)
        if (kind != "user") {
            paint.color = Color.WHITE
            paint.textSize = size * 0.42f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val label = if (kind == "bus" || kind == "congested") "车" else "站"
            canvas.drawText(label, middle, middle - (paint.ascent() + paint.descent()) / 2, paint)
        }
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun focus(point: GeoPoint) {
        if (destroyed || !point.valid) return
        val camera = map.cameraPosition
        val current = GeoPoint(camera.target.latitude, camera.target.longitude)
        if (!positioned) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(point.latLng(), 16f))
        } else if (current.distanceTo(point) >= 0.5) {
            map.moveCamera(CameraUpdateFactory.newLatLng(point.latLng()))
        }
        positioned = true
    }

    fun resize(width: Int, height: Int) {
        if (destroyed || width <= 0 || height <= 0) return
        map.setPointToCenter(width / 2, height / 2)
        currentSelected?.let { focus(it.point) }
    }

    fun resume() {
        if (destroyed || resumed) return
        view.onResume()
        resumed = true
    }

    fun pause() {
        if (destroyed || !resumed) return
        view.onPause()
        resumed = false
    }

    fun destroy() {
        if (destroyed) return
        runCatching { pause() }
        destroyed = true
        runCatching { map.setOnMarkerClickListener(null) }
        runCatching { map.setOnCameraChangeListener(null) }
        runCatching { map.clear() }
        runCatching { view.onDestroy() }
        icons.values.forEach { runCatching { it.recycle() } }
        icons.clear()
        platformClick = {}
        centerChanged = {}
    }

    private data class Scene(
        val platforms: List<Platform>, val selected: Platform?, val userPoint: GeoPoint?,
        val routePoints: List<GeoPoint>, val buses: List<BusVehicle>
    )

    companion object {
        fun create(context: Context): MapSession {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            val view = MapView(context)
            return try {
                view.onCreate(Bundle())
                MapSession(view, context.resources.displayMetrics.density)
            } catch (error: Throwable) {
                runCatching { view.onDestroy() }
                throw error
            }
        }
    }
}

private fun GeoPoint.latLng(): LatLng = LatLng(lat, lng)
