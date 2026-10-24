@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohen.etabus.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mohen.etabus.BuildConfig
import com.mohen.etabus.domain.EtaPresenter
import com.mohen.etabus.model.*
import com.mohen.etabus.platform.BusMap
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun MapScreen(state: BusUiState, model: BusViewModel, actions: UiActions) {
    var selected by remember(state.city.code) { mutableStateOf<Platform?>(null) }
    var dragged by remember(state.city.code, state.center) { mutableStateOf<GeoPoint?>(null) }
    var mapCenter by remember(state.city.code, state.center) { mutableStateOf(state.center) }
    val selectedPlatform = selected
    val records = selectedPlatform?.let { state.arrivals[it.key] }.orEmpty()
    val nearest = nearestArrival(records)
    val searchArea = dragged?.let { point -> state.center?.let { point.distanceTo(it) > state.settings.radius / 2 } ?: true } == true
    Box(Modifier.fillMaxSize()) {
        BusMap(Modifier.fillMaxSize(), state.settings.privacyAccepted, mapCenter,
            platforms = state.nearby.flatMap { it.platforms }, selected = selectedPlatform,
            userPoint = state.userPoint, onCenterChanged = { dragged = it }, onPlatformClick = { platform ->
                actions.network { selected = platform; model.loadPlatform(platform) }
            })
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            PageBar("站点地图", actions.back)
            if (searchArea) Button({ dragged?.let { point -> actions.network { selected = null; mapCenter = point; model.setCenter(point) } } }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Rounded.Search, null); Text("搜索此区域") }
            if (state.loading || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (!BuildConfig.AMAP_CONFIGURED || state.center == null) OutlinedButton(actions.manualCenter, Modifier.align(Alignment.CenterHorizontally)) { Text("手动设置查询中心") }
            state.errors["nearby"]?.let { error -> Box(Modifier.padding(16.dp)) { StaleWarning(error, { actions.network { model.loadNearby() } }) } }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton({
                if (state.userPoint != null && state.locatedCity.removeSuffix("市") == state.city.displayName) mapCenter = state.userPoint
                else actions.locate()
            }) { Icon(Icons.Rounded.MyLocation, null); Text("回到我的位置") }
            if (selectedPlatform != null) BusCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedPlatform.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ selected = null }) { Icon(Icons.Rounded.Close, "关闭站点卡片") }
                }
                state.center?.let { Text(formatDistance(it.distanceTo(selectedPlatform.point)), style = MaterialTheme.typography.bodySmall) }
                StaleWarning(state.errors[selectedPlatform.key], { actions.network { model.loadPlatform(selectedPlatform) } })
                if (selectedPlatform.key in state.loadingKeys && records.isEmpty()) Skeleton(Modifier.fillMaxWidth().height(56.dp))
                else if (nearest != null) RouteRow(nearest, { actions.route(nearest.selection(selectedPlatform)) })
                else Text(if (state.arrivals.containsKey(selectedPlatform.key)) "暂无有效到站记录" else "暂未取得实时到站数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({
                    val group = state.nearby.firstOrNull { it.platforms.any { p -> p.key == selectedPlatform.key } }
                        ?: StationGroup(selectedPlatform.name, listOf(selectedPlatform))
                    actions.station(group)
                }, Modifier.fillMaxWidth()) { Text("查看站点详情") }
            }
        }
    }
}

@Composable
internal fun StationScreen(state: BusUiState, model: BusViewModel, actions: UiActions) {
    val group = state.station
    if (group == null) { Column { PageBar("站点详情", actions.back); StatusPanel("尚未选择站点", "请从附近、搜索或收藏打开站点") }; return }
    val platform = state.platform
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var peekMode by rememberSaveable(group.key) { mutableIntStateOf(1) }
    var mapHeight by remember(group.key, screenHeight) { mutableStateOf(screenHeight * .45f) }
    val peek = when (peekMode) { 0 -> 120.dp; 2 -> (screenHeight - 80.dp).coerceAtLeast(200.dp); else -> screenHeight * .55f }
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    LaunchedEffect(sheetState, density) {
        snapshotFlow { sheetState.hasExpandedState }.first { it }
        snapshotFlow { sheetState.requireOffset() }.collect { offset -> mapHeight = with(density) { offset.toDp() }.coerceAtLeast(1.dp) }
    }
    BottomSheetScaffold(scaffoldState = scaffold, sheetPeekHeight = peek, sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetShadowElevation = 0.dp, sheetTonalElevation = 0.dp, sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetContent = {
            Column(Modifier.fillMaxWidth().height((screenHeight - 80.dp).coerceAtLeast(200.dp))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ model.favoriteStation(group) }) { Icon(if (model.isFavoriteStation(group)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "收藏站点", tint = MaterialTheme.colorScheme.primary) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val distance = platform?.point?.let { state.center?.distanceTo(it) } ?: group.distanceMeters
                    Text(listOfNotNull(distance?.let(::formatDistance), "${group.platforms.size}个同名站台", "当前 ${(state.platformIndex + 1)}").joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("收起", "半屏", "展开").forEachIndexed { index, label -> TextButton({
                        peekMode = index
                        scope.launch { if (index == 2) sheetState.expand() else sheetState.partialExpand() }
                    }) { Text(label, fontWeight = if (peekMode == index) FontWeight.Bold else FontWeight.Normal) } }
                    Spacer(Modifier.weight(1f))
                    if (group.platforms.size > 1) TextButton({ actions.network { model.switchPlatform() } }) { Icon(Icons.Rounded.SwapHoriz, null); Text("切换站台") }
                }
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { actions.network { platform?.let(model::loadPlatform) } }, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { NoticeBanner(state.stationArrivals.mapNotNull { arrival -> arrival.exceptionMsg.takeIf(String::isNotBlank)?.let { "${normalizeRoute(arrival.routeName)}：$it" } }) }
                        item { StaleWarning(platform?.let { state.errors[it.key] }, { actions.network { platform?.let(model::loadPlatform) } }) }
                        if (platform?.key in state.loadingKeys && state.stationArrivals.isEmpty()) items(3) { Skeleton(Modifier.fillMaxWidth().height(70.dp)) }
                        items(state.stationArrivals.distinctBy { it.key }, key = { it.key }) { arrival ->
                            val selection = platform?.let { arrival.selection(it) }
                            RouteRow(arrival, { selection?.let(actions.route) }, selection?.let(model::isFavoriteRoute), { selection?.let { model.favoriteRoute(it) } })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                        if (state.stationArrivals.isEmpty() && platform?.key !in state.loadingKeys) item {
                            val validEmpty = platform != null && state.arrivals.containsKey(platform.key) && !state.errors.containsKey(platform.key)
                            StatusPanel(if (validEmpty) "暂无来车" else "暂无有效到站记录", if (validEmpty) "服务端已返回有效空列表" else "未取得有效实时数据时不会推断为无车", "重试", { actions.network { platform?.let(model::loadPlatform) } })
                        }
                    }
                }
            }
        }) { _ ->
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(mapHeight)) {
                BusMap(Modifier.fillMaxSize(), state.settings.privacyAccepted, platform?.point ?: state.center,
                    platforms = group.platforms, selected = platform, userPoint = state.userPoint,
                    onPlatformClick = { p ->
                        if (platform?.key != p.key && group.platforms.size > 1) actions.network {
                            val target = group.platforms.indexOfFirst { it.key == p.key }
                            val count = group.platforms.size
                            if (target >= 0) repeat((target - state.platformIndex + count) % count) { model.switchPlatform() }
                        }
                    })
                FilledTonalIconButton(actions.locate, Modifier.align(Alignment.BottomStart).padding(16.dp)) { Icon(Icons.Rounded.MyLocation, "定位") }
            }
            PageBar("", actions.back)
        }
    }
}

@Composable
internal fun RouteScreen(state: BusUiState, model: BusViewModel, actions: UiActions) {
    val selection = state.selection
    if (selection == null) { Column { PageBar("线路详情", actions.back); StatusPanel("尚未选择线路") }; return }
    val detail = state.detail
    var fare by remember { mutableStateOf(false) }
    var mapExpanded by rememberSaveable(selection.routeName, selection.direction) { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<String?>(null) }
    val filtered = state.routeArrivals.filter { normalizeRoute(it.routeName) == normalizeRoute(selection.routeName) && it.direction == selection.direction }
    val first = nearestArrival(filtered)
    val arrivalError = selection.platform?.let { state.errors[it.key] }
    val display = when {
        first != null -> EtaPresenter.present(first, detail)
        arrivalError != null -> EtaDisplay("暂无数据", "实时到站未更新 · 请重试", EtaKind.IDLE)
        selection.platform == null -> EtaDisplay("请选择等车站", "需有效站点坐标才能查询", EtaKind.IDLE)
        state.arrivals[selection.platform.key]?.isEmpty() == true -> EtaDisplay("暂无来车", "服务端已返回有效空列表", EtaKind.IDLE)
        else -> EtaDisplay("暂无数据", "未找到本线路与方向的有效到站记录", EtaKind.IDLE)
    }
    Column(Modifier.fillMaxSize()) {
        PageBar(normalizeRoute(selection.routeName), actions.back) {
            IconButton({ model.favoriteRoute(selection) }) { Icon(if (model.isFavoriteRoute(selection)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "收藏线路和等车站", tint = MaterialTheme.colorScheme.primary) }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { actions.network { model.loadRoute() } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    BusCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RouteBadge(selection.routeName, first?.routeColor.orEmpty())
                            Text("${selection.fromStation.ifBlank { detail?.stations?.firstOrNull().orEmpty() }} → ${selection.toStation.ifBlank { detail?.stations?.lastOrNull().orEmpty() }}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (detail != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = ArrivingGreen.copy(alpha = .12f), shape = RoundedCornerShape(50)) { Text("首 ${detail.beginTime.ifBlank { "未提供" }}", Modifier.padding(6.dp), color = ArrivingGreen, style = MaterialTheme.typography.bodySmall) }
                                Surface(color = AlertOrange.copy(alpha = .12f), shape = RoundedCornerShape(50)) { Text("末 ${detail.endTime.ifBlank { "未提供" }}", Modifier.padding(6.dp), color = AlertOrange, style = MaterialTheme.typography.bodySmall) }
                            }
                            TextButton({ fare = true }, contentPadding = PaddingValues(0.dp)) { Text(detail.comments.ifBlank { "票价信息未提供" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Rounded.ChevronRight, "票价全文") }
                        }
                        Text("等车站：${selection.platform?.name ?: detail?.stations?.getOrNull(selection.stationIndex) ?: "请选择"}", color = MaterialTheme.colorScheme.primary)
                        if (state.detailCached) Text("静态缓存 · 正在尝试更新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StaleWarning(state.errors["route"], { actions.network { model.loadRoute() } })
                        StaleWarning(arrivalError, { actions.network { selection.platform?.let(model::loadPlatform) } })
                    }
                }
                if (state.loading && detail == null) item { LoadingCards() }
                item {
                    BusCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text("第一辆", style = MaterialTheme.typography.bodySmall); EtaText(display) }
                            Column(Modifier.widthIn(max = 150.dp), horizontalAlignment = Alignment.End) {
                                Text("第二辆车", style = MaterialTheme.typography.bodySmall)
                                Text("暂不估算", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                                Text("车辆站序与 ETA 尚未验证", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (detail != null && detail.buses.isNotEmpty()) Text("接口返回 ${detail.buses.size} 辆在途车辆；仅展示实际车辆，不推算第二辆到站时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (detail != null) StationStrip(detail, selection.stationIndex, state.alightingIndex, first?.routeColor.orEmpty(), { actions.network { model.chooseWaitingStation(it) } })
                        OutlinedButton({ actions.network { model.reverseDirection() } }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Rounded.SwapHoriz, null); Text("换向 · 请求另一方向") }
                    }
                }
                item { NoticeBanner(state.routeArrivals.mapNotNull { it.exceptionMsg.takeIf(String::isNotBlank)?.let { msg -> "${normalizeRoute(it.routeName)}：$msg" } }) }
                item {
                    BusCard {
                        Row(Modifier.fillMaxWidth().clickable { mapExpanded = !mapExpanded }, verticalAlignment = Alignment.CenterVertically) {
                            Text("线路地图", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(if (mapExpanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
                            Icon(if (mapExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        }
                        if (mapExpanded) {
                            val platforms = detail?.stations.orEmpty().mapIndexedNotNull { index, name -> detail?.stationPoints?.getOrNull(index)?.let { Platform(name, it) } }
                            BusMap(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)), state.settings.privacyAccepted,
                                selection.platform?.point ?: detail?.stationPoints?.firstOrNull { it != null }, platforms = platforms,
                                selected = selection.platform, userPoint = state.userPoint, routePoints = detail?.stationPoints.orEmpty().filterNotNull(), buses = detail?.buses.orEmpty(),
                                onPlatformClick = { p -> detail?.stations?.indices?.firstOrNull { index -> detail.stations[index] == p.name && detail.stationPoints.getOrNull(index) == p.point }?.let { index -> actions.network { model.chooseWaitingStation(index) } } })
                            if (platforms.isEmpty()) Text("本线路未提供真实站点坐标，不绘制猜测路线。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ chooser = "waiting" }, Modifier.weight(1f)) { Text("等车站 ${selection.platform?.name ?: "选择"}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        OutlinedButton({ chooser = "alighting" }, Modifier.weight(1f), enabled = detail != null && selection.stationIndex >= 0) { Text("下车站 ${state.alightingIndex?.let { detail?.stations?.getOrNull(it) } ?: "选择"}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    state.alightingIndex?.let { end -> Text("共 ${end - selection.stationIndex} 站", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
                }
                item { Text("同站线路", style = MaterialTheme.typography.titleMedium) }
                val sameStation = state.routeArrivals.sortedBy { if (normalizeRoute(it.routeName) == normalizeRoute(selection.routeName) && it.direction == selection.direction) 0 else 1 }
                items(sameStation, key = { it.key }) { arrival ->
                    val route = selection.platform?.let { arrival.selection(it) }
                    BusCard { RouteRow(arrival, { route?.let(actions.route) }, route?.let(model::isFavoriteRoute), { route?.let { model.favoriteRoute(it) } }, normalizeRoute(arrival.routeName) == normalizeRoute(selection.routeName) && arrival.direction == selection.direction) }
                }
                if (sameStation.isEmpty()) item { Text("${if (selection.platform == null) "请选择带坐标的等车站" else "暂无同站线路数据"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (fare && detail != null) AlertDialog(onDismissRequest = { fare = false }, title = { Text("首末班与票价") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("首班 ${detail.beginTime}\n末班 ${detail.endTime}\n\n${detail.comments.ifBlank { "服务端未提供票价信息" }}") } }, confirmButton = { TextButton({ fare = false }) { Text("知道了") } })
    if (chooser != null && detail != null) {
        val alighting = chooser == "alighting"
        val indices = detail.stations.indices.filter { !alighting || it > selection.stationIndex }
        AlertDialog(onDismissRequest = { chooser = null }, title = { Text(if (alighting) "选择下车站" else "选择等车站") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (alighting) item { TextButton({ model.chooseAlightingStation(null); chooser = null }) { Text("清除下车站") } }
                items(indices) { index -> ListItem(headlineContent = { Text("${index + 1}  ${detail.stations[index]}") }, supportingContent = { if (!alighting && detail.stationPoints.getOrNull(index) == null) Text("坐标缺失，无法查询实时到站") }, modifier = Modifier.clickable {
                    chooser = null
                    if (alighting) model.chooseAlightingStation(index) else actions.network { model.chooseWaitingStation(index) }
                }) }
                if (indices.isEmpty()) item { Text("当前等车站之后没有可选站点") }
            }
        }, confirmButton = { TextButton({ chooser = null }) { Text("关闭") } })
    }
}

@Composable
fun StationStrip(detail: RouteDetail, waitingIndex: Int, alightingIndex: Int?, explicitColor: String = "", onStationClick: (Int) -> Unit) {
    val list = rememberLazyListState()
    val color = routeColor(detail.routeName, explicitColor)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.surface
    val alert = AlertOrange
    val transition = rememberInfiniteTransition(label = "waitingStation")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1500)), label = "stationPulse")
    val namedVehicles = detail.buses.filter { bus -> bus.stationName.isNotBlank() && detail.stations.count { it == bus.stationName } == 1 }
    val passed = namedVehicles.map { detail.stations.indexOf(it.stationName) }.filter { it >= 0 }.minOrNull() ?: -1
    LaunchedEffect(waitingIndex, detail.stations) { if (waitingIndex in detail.stations.indices) list.animateScrollToItem(waitingIndex) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = ((maxWidth - 72.dp) / 2).coerceAtLeast(0.dp)
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = side), modifier = Modifier.fillMaxWidth().heightIn(min = 245.dp, max = 350.dp)) {
            itemsIndexed(detail.stations) { index, name ->
                val vehicles = namedVehicles.filter { it.stationName == name }
                val waiting = index == waitingIndex
                val highlighted = alightingIndex != null && index >= waitingIndex && index <= alightingIndex
                Column(Modifier.width(72.dp).clickable { onStationClick(index) }.semantics { contentDescription = "第${index + 1}站 $name${if (waiting) "，当前等车站" else "，点击设为等车站"}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(45.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (vehicles.isNotEmpty()) Surface(shape = RoundedCornerShape(8.dp), color = color,
                            border = if (vehicles.any { it.statusType == "2" }) BorderStroke(2.dp, alert) else null) {
                            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.DirectionsBus, "${vehicles.size}辆车${if (vehicles.any { it.statusType == "2" }) "，拥堵" else ""}", Modifier.size(20.dp), tint = Color.White)
                                if (vehicles.size > 1) Text(vehicles.size.toString(), color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(38.dp)) {
                        val y = size.height / 2
                        val middle = size.width / 2
                        val lineColor = if (index < passed) color.copy(alpha = .4f) else color
                        val width = if (highlighted) 10.dp.toPx() else 6.dp.toPx()
                        if (index > 0) drawLine(lineColor, Offset(0f, y), Offset(middle, y), width)
                        if (index < detail.stations.lastIndex) drawLine(lineColor, Offset(middle, y), Offset(size.width, y), width)
                        if (waiting) {
                            drawCircle(primary.copy(alpha = (1 - pulse) * .4f), (10 + pulse * 8).dp.toPx(), Offset(middle, y))
                            drawCircle(primary, 9.dp.toPx(), Offset(middle, y))
                            drawCircle(outline, 3.dp.toPx(), Offset(middle, y))
                        } else { drawCircle(outline, 6.dp.toPx(), Offset(middle, y)); drawCircle(color, 6.dp.toPx(), Offset(middle, y), style = Stroke(2.dp.toPx())) }
                    }
                    Text((index + 1).toString(), style = MaterialTheme.typography.bodySmall, color = if (waiting) primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(name.toCharArray().joinToString("\n"), Modifier.padding(top = 6.dp, bottom = 8.dp), textAlign = TextAlign.Center,
                        color = if (waiting) primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (waiting) FontWeight.Bold else FontWeight.Normal, lineHeight = 19.sp, fontSize = 15.sp)
                    if (waiting) Icon(Icons.Rounded.ArrowUpward, "等车站", Modifier.size(14.dp), tint = primary)
                }
            }
        }
    }
    if (detail.buses.isNotEmpty()) Text("车辆仅按唯一同名站映射；站序基准待运营样本验证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}