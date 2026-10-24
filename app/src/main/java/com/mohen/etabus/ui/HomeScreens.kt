@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohen.etabus.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohen.etabus.BuildConfig
import com.mohen.etabus.model.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(
    state: BusUiState,
    model: BusViewModel,
    actions: UiActions,
    locationGranted: Boolean,
    locationPermissionPending: Boolean
) {
    val showInitialSkeleton = state.settings.privacyAccepted && state.nearby.isEmpty() && (
        state.loading || state.locating ||
            (BuildConfig.AMAP_CONFIGURED && state.center == null &&
                (locationPermissionPending || (locationGranted && state.errors["location"] == null))) ||
            (state.center != null && !state.searchedNearby && state.errors["nearby"] == null)
        )
    var history by rememberSaveable { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var appliedRadius by rememberSaveable(state.city.code) { mutableIntStateOf(state.settings.radius) }
    val list = rememberLazyListState()
    LaunchedEffect(history) { model.setHomeHistory(history) }
    LaunchedEffect(state.settings.radius, history) {
        if (!history && appliedRadius != state.settings.radius) {
            appliedRadius = state.settings.radius
            if (state.center != null && state.settings.privacyAccepted) model.loadNearby(state.settings.radius)
        }
    }
    LaunchedEffect(list, history, state.nearby) {
        if (!history) snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("nearby:") }?.removePrefix("nearby:") }.toSet() }
            .distinctUntilChanged().collect { model.setVisibleNearby(it) }
        else model.setVisibleNearby(emptySet())
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ actions.navigate("cities") }) { Text(state.city.displayName); Icon(Icons.Rounded.ArrowDropDown, null) }
            Surface(Modifier.weight(1f).clickable { actions.navigate("search") }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Search, null); Text("搜线路、站点", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("附近", "历史").forEachIndexed { index, title ->
                Column(Modifier.clickable { history = index == 1 }.padding(end = 24.dp, top = 10.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = if (history == (index == 1)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(22.dp).height(3.dp).background(if (history == (index == 1)) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50)))
                }
            }
            Spacer(Modifier.weight(1f))
            if (history && state.history.isNotEmpty()) TextButton({ clear = true }) { Text("清空") }
            else TextButton({ actions.navigate("map") }) { Icon(Icons.Rounded.Map, null); Text("地图") }
        }
        if (history) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.history.isEmpty()) item { StatusPanel("还没有浏览记录", "查看线路或站点后，会在此保留最近 50 条记录") }
                items(state.history.take(50), key = { it.id }) { item ->
                    SwipeDelete(item.id, { model.deleteHistory(item.id) }) {
                        SavedSummary(item, { item.route?.let(actions.route) ?: item.station?.let(actions.station) })
                    }
                }
            }
        } else PullToRefreshBox(isRefreshing = !showInitialSkeleton && (state.refreshing || state.locating), onRefresh = actions.refreshNearby, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showInitialSkeleton) {
                    item(key = "center") {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Skeleton(Modifier.weight(1f).height(14.dp))
                            Skeleton(Modifier.width(72.dp).height(28.dp))
                            Skeleton(Modifier.size(32.dp))
                        }
                    }
                    item(key = "initial-loading") { LoadingCards() }
                    return@LazyColumn
                }
                item(key = "center") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.center == null) "尚未设置当前城市查询中心" else "查询范围 ${state.settings.radius} 米", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(actions.manualCenter) { Text("设置中心") }
                        IconButton(actions.locate) { Icon(Icons.Rounded.MyLocation, "定位") }
                    }
                }
                if (!state.settings.privacyAccepted) item {
                    StatusPanel("先了解隐私与数据使用", "同意前不联网、不定位、不初始化地图。可先浏览本地设置，或进入搜索页。", "查看并同意", actions.privacy)
                    TextButton({ actions.navigate("search") }, Modifier.fillMaxWidth()) { Text("前往搜索") }
                }
                else if (state.center == null) item {
                    when {
                        !BuildConfig.AMAP_CONFIGURED -> StatusPanel("尚未配置地图与定位服务", "请手动设置当前城市查询中心；仍可搜索线路", "设置查询中心", actions.manualCenter)
                        locationGranted -> StatusPanel("定位未成功", state.errors["location"] ?: "请检查系统定位开关和网络，或手动设置查询中心", "重新定位", actions.locate)
                        else -> StatusPanel("允许定位后查看附近站点", "也可以手动设置当前城市查询中心", "授权定位", actions.locate)
                    }
                    OutlinedButton(actions.manualCenter, Modifier.fillMaxWidth()) { Text("手动设置查询中心") }
                    TextButton({ actions.navigate("search") }, Modifier.fillMaxWidth()) { Text("搜索线路、站点") }
                }
                item(key = "error") { StaleWarning(state.errors["nearby"], { actions.network { model.loadNearby() } }) }
                if (state.searchedNearby && state.nearby.isEmpty() && state.errors["nearby"] == null && !state.loading) item {
                    StatusPanel("附近未找到公交站", "当前设置 ${state.settings.radius} 米；服务端可能未完整覆盖，扩大范围不保证返回更多。", "临时扩大到 3000 米", { actions.network { model.loadNearby(3000) } })
                }
                itemsIndexed(state.nearby, key = { _, group -> "nearby:${group.key}" }) { index, group ->
                    val expanded = group.key in state.expanded
                    val platforms = if (expanded) group.platforms else group.platforms.take(1)
                    val arrivals = platforms.flatMap { state.arrivals[it.key].orEmpty() }.distinctBy { "${it.routeName}|${it.direction}|${it.stationIndex}" }
                    StationCard(group, arrivals, expanded, index == 0, platforms.any { it.key in state.loadingKeys },
                        platforms.firstNotNullOfOrNull { state.errors[it.key] }, { actions.station(group) },
                        { actions.network { model.toggleStation(group) } }, { arrival, fallback ->
                            val platform = platforms.firstOrNull { state.arrivals[it.key]?.contains(arrival) == true } ?: fallback
                            actions.route(arrival.selection(platform))
                        }, { actions.network { platforms.forEach(model::loadPlatform) } })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    if (clear) ConfirmDialog("清空浏览历史？", "仅清除当前城市的浏览记录，此操作无法撤销。", { clear = false }, { model.clearHistory() })
}

@Composable
internal fun SearchScreen(state: BusUiState, model: BusViewModel, actions: UiActions) {
    var query by rememberSaveable(state.city.code) { mutableStateOf(state.searchQuery) }
    val cachedRoutes = remember(state.city.code) { mutableStateMapOf<String, List<RouteSummary>>() }
    val cachedStations = remember(state.city.code) { mutableStateMapOf<String, List<StationGroup>>() }
    LaunchedEffect(state.searchQuery, state.searchLoading, state.searchRoutes, state.searchStations, state.searchWarning) {
        if (!state.searchLoading && state.searchQuery.isNotBlank()) {
            if (state.searchWarning == null || state.searchRoutes.isNotEmpty()) cachedRoutes[state.searchQuery] = state.searchRoutes
            if (state.searchWarning == null || state.searchStations.isNotEmpty()) cachedStations[state.searchQuery] = state.searchStations
        }
    }
    val shownRoutes = if (state.searchWarning != null && state.searchRoutes.isEmpty()) cachedRoutes[state.searchQuery].orEmpty() else state.searchRoutes
    val shownStations = if (state.searchWarning != null && state.searchStations.isEmpty()) cachedStations[state.searchQuery].orEmpty() else state.searchStations
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(actions.back) { Icon(Icons.Rounded.ArrowBack, "返回") }
            OutlinedTextField(query, { value -> query = value; if (value.isBlank()) model.search(value) else actions.network { model.search(value) } },
                Modifier.weight(1f), placeholder = { Text("搜线路、站点") }, singleLine = true, shape = RoundedCornerShape(50),
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = ""; model.search("") }) { Icon(Icons.Rounded.Close, "清空输入") } })
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (query.isBlank()) {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("最近搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton({ model.clearSearchHistory() }) { Text("清空") } } }
                items(state.settings.searchHistory.take(10), key = { it }) { term ->
                    ListItem(headlineContent = { Text(term) }, leadingContent = { Icon(Icons.Rounded.History, null) }, modifier = Modifier.clickable { query = term; actions.network { model.search(term) } })
                }
                if (state.settings.searchHistory.isEmpty()) item { StatusPanel("输入线路号或站点名称", "站点查询需要同城坐标；未配置定位时可手动设置查询中心", "设置查询中心", actions.manualCenter) }
            } else {
                if (!state.settings.privacyAccepted) item { StatusPanel("在线搜索需要您的同意", "先查看隐私说明，仍可返回浏览本地设置。", "同意并搜索", { actions.network { model.search(query) } }) }
                if (state.searchLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在查询，暂保留已有结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.searchWarning != null) item { BusCard { Text("未更新 · ${state.searchWarning}", style = MaterialTheme.typography.bodySmall); Row { TextButton({ actions.network { model.search(query) } }) { Text("重试") }; TextButton(actions.manualCenter) { Text("设置查询中心") } } } }
                if (shownRoutes.isNotEmpty()) item { Text("线路", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(shownRoutes, key = { index, route -> "${route.routeName}|${route.direction}|${route.stationName}|$index" }) { _, route ->
                    BusCard(Modifier.clickable {
                        model.rememberSearch(query)
                        val realPlatform = shownStations.filter { it.name == route.stationName }.flatMap { it.platforms }.distinctBy { it.key }.singleOrNull()
                        actions.route(RouteSelection(route.routeName, route.direction, route.fromStation, route.toStation, realPlatform, route.stationIndex))
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RouteBadge(route.routeName)
                            Column(Modifier.weight(1f)) {
                                Text("${route.fromStation.ifBlank { "起点待确认" }} → ${route.toStation.ifBlank { "终点待确认" }}", style = MaterialTheme.typography.bodyMedium)
                                Text("方向 ${route.direction}${route.stationName.takeIf(String::isNotBlank)?.let { " · 途经 $it" }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
                if (shownStations.isNotEmpty()) item { Text("站点", style = MaterialTheme.typography.titleMedium) }
                items(shownStations, key = { "station:${it.key}" }) { group ->
                    BusCard(Modifier.clickable { model.rememberSearch(query); actions.station(group) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Signpost, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(group.name, style = MaterialTheme.typography.titleMedium); Text("${group.platforms.size}个站台 · ${group.platforms.maxOfOrNull { it.routeCount } ?: 0}条线路", style = MaterialTheme.typography.bodySmall) }
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
                if (!state.searchLoading && shownRoutes.isEmpty() && shownStations.isEmpty() && state.searchWarning == null && state.settings.privacyAccepted) item { StatusPanel("没有找到「$query」相关的线路或站点", "可尝试完整站名或其他线路号") }
            }
        }
    }
}

@Composable
internal fun CitiesScreen(state: BusUiState, model: BusViewModel, actions: UiActions) {
    var query by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cities = remember(query, model.cities) { model.cities.filter { query.isBlank() || it.name.contains(query, true) || it.code.contains(query) || model.cityInitial(it).equals(query, true) } }
    val groups = remember(cities) { cities.groupBy(model::cityInitial).toSortedMap() }
    val indices = remember(groups) { var index = 3; groups.mapValues { (_, items) -> val start = index; index += items.size + 1; start } }
    Column(Modifier.fillMaxSize()) {
        PageBar("选择城市", actions.back)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("搜索城市、区号或首字母") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(50))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 28.dp), contentPadding = PaddingValues(16.dp)) {
                item {
                    Text("定位城市", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val located = model.cities.firstOrNull { it.displayName == state.locatedCity.removeSuffix("市") }
                        TextButton({ if (located != null) actions.useCity(located.code) else actions.locate() }) { Icon(Icons.Rounded.MyLocation, null); Text(state.locatedCity.ifBlank { "点击定位城市" }) }
                        if (state.locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                item { Text("最近使用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                item {
                    Column { state.settings.recentCities.take(6).mapNotNull { code -> model.cities.firstOrNull { it.code == code } }.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { city -> OutlinedButton({ actions.useCity(city.code) }) { Text(city.displayName) } } }
                    } }
                }
                groups.forEach { (initial, items) ->
                    item(key = "letter:$initial") { Text(initial, Modifier.fillMaxWidth().padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                    items(items, key = { it.code }) { city ->
                        Row(Modifier.fillMaxWidth().clickable { actions.useCity(city.code) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(city.displayName, Modifier.weight(1f)); if (city.code == state.city.code) Icon(Icons.Rounded.Check, "当前城市", tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (cities.isEmpty()) item { StatusPanel("没有找到城市") }
            }
            Column(Modifier.align(Alignment.CenterEnd).width(28.dp).fillMaxHeight().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                groups.keys.forEach { letter -> Text(letter, Modifier.clickable { scope.launch { list.animateScrollToItem(indices.getValue(letter)) } }.padding(vertical = 3.dp, horizontal = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}