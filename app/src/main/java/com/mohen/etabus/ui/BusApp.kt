@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohen.etabus.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.*
import com.mohen.etabus.BuildConfig
import com.mohen.etabus.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class UiActions(
    val back: () -> Unit,
    val navigate: (String) -> Unit,
    val station: (StationGroup) -> Unit,
    val route: (RouteSelection) -> Unit,
    val network: (() -> Unit) -> Unit,
    val locate: () -> Unit,
    val manualCenter: () -> Unit,
    val refreshNearby: () -> Unit,
    val useCity: (String) -> Unit,
    val privacy: () -> Unit
)

@Composable
fun BusApp(model: BusViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    BusTheme(state.settings) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val destination = entry?.destination?.route ?: "home"
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val hasLocationPermission = {
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        var locationGranted by remember { mutableStateOf(hasLocationPermission()) }
        var permissionRequested by rememberSaveable { mutableStateOf(false) }
        var permissionInFlight by rememberSaveable { mutableStateOf(false) }
        var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val snackbar = remember { SnackbarHostState() }
        var privacySeen by rememberSaveable { mutableStateOf(false) }
        var privacyDialog by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
        var centerDialog by remember { mutableStateOf(false) }
        val currentState by rememberUpdatedState(state)
        val gate: (() -> Unit) -> Unit = { task ->
            if (currentState.settings.privacyAccepted) task()
            else { pending = task; privacyDialog = true }
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionInFlight = false
            locationGranted = hasLocationPermission()
            if (locationGranted) model.locate()
            else model.message("未授予定位权限，仍可搜索线路或手动设置查询中心")
        }
        val locate: () -> Unit = {
            gate {
                locationGranted = hasLocationPermission()
                if (!BuildConfig.AMAP_CONFIGURED) {
                    model.message("尚未配置高德 Key，请手动设置当前城市查询中心")
                    centerDialog = true
                } else if (locationGranted) {
                    model.locate()
                } else if (!permissionInFlight) {
                    permissionRequested = true
                    permissionInFlight = true
                    permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
        }
        DisposableEffect(lifecycle, context) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        locationGranted = hasLocationPermission()
                        resumed = true
                    }
                    Lifecycle.Event.ON_PAUSE -> resumed = false
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(state.ready, state.settings.privacyAccepted) {
            if (state.ready && !state.settings.privacyAccepted && !privacySeen) { privacySeen = true; privacyDialog = true }
        }
        LaunchedEffect(destination) { model.onPage(destination) }
        LaunchedEffect(state.ready, state.settings.privacyAccepted, destination, resumed, locationGranted, permissionInFlight) {
            if (state.ready && state.settings.privacyAccepted && destination == "home" && resumed && BuildConfig.AMAP_CONFIGURED) {
                if (locationGranted && !permissionInFlight && !model.ui.value.locating) model.locate()
                else if (!locationGranted && !permissionRequested && !permissionInFlight) locate()
            }
        }
        LaunchedEffect(lifecycle, model) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.foregroundRefreshLoop() }
        }
        LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
        val actions = UiActions(
            back = { nav.popBackStack() },
            navigate = { route -> nav.navigate(route) { launchSingleTop = true } },
            station = { group -> gate { model.openStation(group); nav.navigate("station") { launchSingleTop = true } } },
            route = { route -> gate { model.openRoute(route); nav.navigate("route") { launchSingleTop = true } } },
            network = gate,
            locate = locate,
            manualCenter = { centerDialog = true },
            refreshNearby = { gate {
                val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted && BuildConfig.AMAP_CONFIGURED) model.locate()
                else if (currentState.center != null) model.loadNearby()
                else locate()
            } },
            useCity = { code -> scope.launch {
                model.useCity(code).join()
                model.ui.first { it.city.code == code }
                nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true }
            } },
            privacy = { privacyDialog = true }
        )
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (destination in listOf("home", "favorites", "settings")) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf(Triple("home", "首页", Icons.Rounded.Home), Triple("favorites", "收藏", Icons.Rounded.StarBorder), Triple("settings", "设置", Icons.Rounded.Settings)).forEach { (route, label, icon) ->
                        NavigationBarItem(selected = destination == route, onClick = {
                            nav.navigate(route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
                        }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!state.ready) LoadingCards()
                else NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(state, model, actions, locationGranted,
                            locationPermissionPending = !locationGranted && (!permissionRequested || permissionInFlight))
                    }
                    composable("search") { SearchScreen(state, model, actions) }
                    composable("cities") { CitiesScreen(state, model, actions) }
                    composable("map") { MapScreen(state, model, actions) }
                    composable("station") { StationScreen(state, model, actions) }
                    composable("route") { RouteScreen(state, model, actions) }
                    composable("favorites") { FavoritesScreen(state, model, actions, snackbar) }
                    composable("settings") { SettingsScreen(state, model, actions) }
                    composable("license") { LicenseScreen(actions.back, actions.network) }
                }
            }
        }
        if (privacyDialog) AlertDialog(
            onDismissRequest = { privacyDialog = false; pending = null },
            title = { Text("隐私与数据使用说明") },
            text = { Text("秒查公交的线路与到站信息来自掌上公交服务端，准确性及各城市可用性以实际返回为准。查询时会向对应城市服务端发送城市、站名及查询中心坐标。地图与定位使用高德地图，高德会处理位置信息及提供服务所需的设备信息。\n\n仅在您同意后访问网络或初始化地图/定位 SDK。同意后会请求系统定位权限；如果已经授权，将直接定位并刷新附近站点。您可拒绝并浏览本地设置、历史与收藏，也可进入搜索页；发起在线查询前会再次询问。") },
            confirmButton = { TextButton({
                privacyDialog = false
                val action = pending
                pending = null
                scope.launch {
                    model.acceptPrivacy(true).join()
                    model.ui.first { it.settings.privacyAccepted }
                    action?.invoke()
                }
            }) { Text("同意并继续") } },
            dismissButton = { TextButton({ privacySeen = true; privacyDialog = false; pending = null }) { Text("暂不同意") } }
        )
        if (centerDialog) CenterDialog(state.city.displayName, state.center, { centerDialog = false }) { point ->
            centerDialog = false
            gate { model.setCenter(point) }
        }
    }
}

@Composable
private fun CenterDialog(city: String, center: GeoPoint?, dismiss: () -> Unit, save: (GeoPoint) -> Unit) {
    var lat by remember { mutableStateOf(center?.lat?.toString().orEmpty()) }
    var lng by remember { mutableStateOf(center?.lng?.toString().orEmpty()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("设置 $city 查询中心") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("请输入当前城市的 GCJ-02 纬度和经度。不会使用其他城市的默认坐标；切换城市后须重新设置。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(lat, { lat = it; error = false }, label = { Text("纬度 lat（-90 至 90）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(lng, { lng = it; error = false }, label = { Text("经度 lng（-180 至 180）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            if (error) Text("请输入有效的经纬度，不能同时为 0", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({
        val latitude = lat.toDoubleOrNull()
        val longitude = lng.toDoubleOrNull()
        val point = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
        if (point?.valid == true) save(point) else error = true
    }) { Text("保存并搜索附近") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}