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
            if (!BuildConfig.AMAP_CONFIGURED || state.center == null) OutlinedButton(actions.manualCenter, Modifier.align(Alignment.CenterHorizontally)) { Text("手动设置位置") }
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
                else Text(if (state.arrivals.containsKey(selectedPlatform.key)) "暂无来车" else "暂无到站信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (group == null) { Column { PageBar("站点详情", actions.back); StatusPanel("尚未选择站点") }; return }
    val platform = state.platform
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var mapHeight by remember(group.key, screenHeight) { mutableStateOf(screenHeight * .45f) }
    val peek = screenHeight * .55f
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
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleLarge)
                        val distance = platform?.point?.let { state.center?.distanceTo(it) } ?: group.distanceMeters
                        val info = listOfNotNull(distance?.let(::formatDistance), group.platforms.size.takeIf { it > 1 }?.let { "${it}个同名站台" })
                        if (info.isNotEmpty()) Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (group.platforms.size > 1) OutlinedButton({ actions.network { model.switchPlatform() } }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("切换站台")
                    }
                    IconButton({ model.favoriteStation(group) }) { Icon(if (model.isFavoriteStation(group)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "收藏站点", tint = MaterialTheme.colorScheme.primary) }
                }
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { actions.network { platform?.let(model::loadPlatform) } }, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { NoticeBanner(state.stationNotices) }
                        item { StaleWarning(state.errors["notices"], { actions.network { platform?.let(model::loadPlatform) } }) }
                        item { StaleWarning(platform?.let { state.errors[it.key] }, { actions.network { platform?.let(model::loadPlatform) } }) }
                        if (platform?.key in state.loadingKeys && state.stationArrivals.isEmpty()) items(3) { Skeleton(Modifier.fillMaxWidth().height(70.dp)) }
                        itemsIndexed(state.stationArrivals.distinctBy { it.key }, key = { _, it -> it.key }) { index, arrival ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            val selection = platform?.let { arrival.selection(it) }
                            RouteRow(arrival, { selection?.let(actions.route) }, selection?.let(model::isFavoriteRoute), { selection?.let { model.favoriteRoute(it) } })
                        }
                        if (state.stationArrivals.isEmpty() && platform?.key !in state.loadingKeys) item {
                            val validEmpty = platform != null && state.arrivals.containsKey(platform.key) && !state.errors.containsKey(platform.key)
                            StatusPanel(if (validEmpty) "暂无来车" else "暂无到站信息", if (validEmpty) "" else "下拉或点击重试", "重试", { actions.network { platform?.let(model::loadPlatform) } })
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
        arrivalError != null -> EtaDisplay("暂无数据", "未更新 · 请重试", EtaKind.IDLE)
        selection.platform == null -> EtaDisplay("请选择等车站", "", EtaKind.IDLE)
        state.arrivals[selection.platform.key]?.isEmpty() == true -> EtaDisplay("暂无来车", "", EtaKind.IDLE)
        else -> EtaDisplay("暂无数据", "", EtaKind.IDLE)
    }
    val waitingName = selection.platform?.name ?: detail?.stations?.getOrNull(selection.stationIndex)
    val alightingName = state.alightingIndex?.let { detail?.stations?.getOrNull(it) }
    Column(Modifier.fillMaxSize()) {
        PageBar(normalizeRoute(selection.routeName), actions.back) {
            IconButton({ actions.network { model.reverseDirection() } }) { Icon(Icons.Rounded.SwapHoriz, "换向") }
            IconButton({ model.favoriteRoute(selection) }) { Icon(if (model.isFavoriteRoute(selection)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "收藏", tint = MaterialTheme.colorScheme.primary) }
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { actions.network { model.loadRoute() } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RouteBadge(selection.routeName, first?.routeColor.orEmpty())
                            Text("开往 ${selection.toStation.ifBlank { detail?.stations?.lastOrNull().orEmpty() }}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (detail != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { fare = true }, verticalAlignment = Alignment.CenterVertically) {
                            val info = listOfNotNull(
                                detail.beginTime.takeIf(String::isNotBlank)?.let { "首 $it" },
                                detail.endTime.takeIf(String::isNotBlank)?.let { "末 $it" },
                                detail.comments.takeIf(String::isNotBlank)
                            ).joinToString("  ")
                            Text(info, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ChevronRight, "首末班与票价", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    StaleWarning(state.errors["route"], { actions.network { model.loadRoute() } })
                    StaleWarning(arrivalError, { actions.network { selection.platform?.let(model::loadPlatform) } })
                }
                item { NoticeBanner(state.routeNotices) }
                item { StaleWarning(state.errors["notices"], { actions.network { model.loadRoute() } }) }
                item {
                    BusCard {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = detail != null) { chooser = "waiting" }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(waitingName ?: "选择等车站", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text("切换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val second = if (display.live) EtaPresenter.secondBus(detail, selection.stationIndex, first) else null
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text("第一辆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                EtaText(display, alignment = Alignment.Start)
                            }
                            if (second != null) {
                                Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outline).align(Alignment.CenterVertically))
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text("第二辆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    EtaText(second, alignment = Alignment.Start)
                                }
                            }
                        }
                        if (state.loading && detail == null) Skeleton(Modifier.fillMaxWidth().height(200.dp))
                        if (detail != null) StationStrip(detail, selection.stationIndex, state.alightingIndex, first?.routeColor.orEmpty(), { actions.network { model.chooseWaitingStation(it) } })
                        if (detail != null && selection.stationIndex >= 0) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { chooser = "alighting" }, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (alightingName == null) "选择下车站" else "到 $alightingName · 共 ${state.alightingIndex!! - selection.stationIndex} 站", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, color = if (alightingName == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    BusCard {
                        Row(Modifier.fillMaxWidth().clickable { mapExpanded = !mapExpanded }, verticalAlignment = Alignment.CenterVertically) {
                            Text("线路地图", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(if (mapExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (mapExpanded) "收起" else "展开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (mapExpanded) {
                            val platforms = detail?.stations.orEmpty().mapIndexedNotNull { index, name -> detail?.stationPoints?.getOrNull(index)?.let { Platform(name, it) } }
                            BusMap(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)), state.settings.privacyAccepted,
                                selection.platform?.point ?: detail?.stationPoints?.firstOrNull { it != null }, platforms = platforms,
                                selected = selection.platform, userPoint = state.userPoint, routePoints = detail?.stationPoints.orEmpty().filterNotNull(), buses = detail?.buses.orEmpty(),
                                onPlatformClick = { p -> detail?.stations?.indices?.firstOrNull { index -> detail.stations[index] == p.name && detail.stationPoints.getOrNull(index) == p.point }?.let { index -> actions.network { model.chooseWaitingStation(index) } } })
                            if (platforms.isEmpty()) Text("暂无线路地图", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val sameStation = state.routeArrivals.filterNot { normalizeRoute(it.routeName) == normalizeRoute(selection.routeName) && it.direction == selection.direction }
                if (sameStation.isNotEmpty()) item {
                    BusCard {
                        Text("${waitingName.orEmpty()} 其他线路", style = MaterialTheme.typography.titleMedium)
                        sameStation.forEachIndexed { index, arrival ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            val route = selection.platform?.let { arrival.selection(it) }
                            RouteRow(arrival, { route?.let(actions.route) })
                        }
                    }
                }
            }
        }
    }
    if (fare && detail != null) AlertDialog(onDismissRequest = { fare = false }, title = { Text("首末班与票价") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("首班 ${detail.beginTime}\n末班 ${detail.endTime}\n\n${detail.comments.ifBlank { "暂无票价信息" }}") } }, confirmButton = { TextButton({ fare = false }) { Text("知道了") } })
    if (chooser != null && detail != null) {
        val alighting = chooser == "alighting"
        val indices = detail.stations.indices.filter { !alighting || it > selection.stationIndex }
        AlertDialog(onDismissRequest = { chooser = null }, title = { Text(if (alighting) "选择下车站" else "选择等车站") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (alighting) item { TextButton({ model.chooseAlightingStation(null); chooser = null }) { Text("清除下车站") } }
                items(indices) { index -> ListItem(headlineContent = { Text("${index + 1}  ${detail.stations[index]}") }, supportingContent = { if (!alighting && detail.stationPoints.getOrNull(index) == null) Text("该站暂不支持实时查询") }, modifier = Modifier.clickable {
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
    val transition = rememberInfiniteTransition(label = "waitingStation")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1500)), label = "stationPulse")
    val vehiclesOnRoute = detail.buses.filter { it.index in detail.stations.indices }
    val passed = vehiclesOnRoute.minOfOrNull { if (it.approaching) it.index - 1 else it.index } ?: -1
    LaunchedEffect(waitingIndex, detail.stations) { if (waitingIndex in detail.stations.indices) list.animateScrollToItem(waitingIndex) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = ((maxWidth - 72.dp) / 2).coerceAtLeast(0.dp)
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = side), modifier = Modifier.fillMaxWidth().heightIn(min = 245.dp, max = 350.dp)) {
            itemsIndexed(detail.stations) { index, name ->
                val atStation = vehiclesOnRoute.filter { it.index == index && !it.approaching }
                val approaching = vehiclesOnRoute.filter { it.index == index && it.approaching }
                val waiting = index == waitingIndex
                val highlighted = alightingIndex != null && index >= waitingIndex && index <= alightingIndex
                Column(Modifier.width(72.dp).clickable { onStationClick(index) }.semantics { contentDescription = "第${index + 1}站 $name${if (waiting) "，当前等车站" else "，点击设为等车站"}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(45.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (atStation.isNotEmpty()) VehicleBadge(atStation.size, color, "${atStation.size}辆车已到站")
                        if (approaching.isNotEmpty()) VehicleBadge(approaching.size, color, "${approaching.size}辆车即将到达", Modifier.offset(x = (-36).dp))
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
}

@Composable
private fun VehicleBadge(count: Int, color: Color, description: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = color) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DirectionsBus, description, Modifier.size(20.dp), tint = Color.White)
            if (count > 1) Text(count.toString(), color = Color.White, fontSize = 11.sp)
        }
    }
}