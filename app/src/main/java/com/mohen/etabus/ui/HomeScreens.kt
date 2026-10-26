@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohen.etabus.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(RoundedCornerShape(50)).clickable { actions.navigate("cities") }.padding(start = 4.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.city.displayName, style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Rounded.ArrowDropDown, "切换城市")
            }
            Surface(Modifier.weight(1f).clip(RoundedCornerShape(50)).clickable { actions.navigate("search") }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("搜线路、站点", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("附近", "历史").forEachIndexed { index, title ->
                val selected = history == (index == 1)
                val color by androidx.compose.animation.animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabColor")
                val indicator by androidx.compose.animation.core.animateDpAsState(if (selected) 18.dp else 0.dp, label = "tabIndicator")
                Column(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab) { history = index == 1 }
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Text(title, color = color, style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(indicator).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                }
            }
            Spacer(Modifier.weight(1f))
            if (history) {
                if (state.history.isNotEmpty()) TextButton({ clear = true }) { Text("清空") }
            } else {
                IconButton({ actions.navigate("map") }) { Icon(Icons.Rounded.Map, "地图", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(actions.locate) {
                    if (state.locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.MyLocation, "重新定位", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (history) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.history.isEmpty()) item { StatusPanel("还没有浏览记录", "查看过的线路和站点会显示在这里") }
                items(state.history.take(50), key = { it.id }) { item ->
                    SwipeDelete(item.id, { model.deleteHistory(item.id) }) {
                        SavedSummary(item, { item.route?.let(actions.route) ?: item.station?.let(actions.station) })
                    }
                }
            }
        } else PullToRefreshBox(isRefreshing = !showInitialSkeleton && (state.refreshing || state.locating), onRefresh = actions.refreshNearby, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showInitialSkeleton) {
                    item(key = "initial-loading") { LoadingCards() }
                    return@LazyColumn
                }
                if (!state.settings.privacyAccepted) item {
                    StatusPanel("欢迎使用秒查公交", "同意隐私说明后即可查看附近站点和实时到站", "查看并同意", actions.privacy)
                }
                else if (state.center == null) item {
                    when {
                        !BuildConfig.AMAP_CONFIGURED -> StatusPanel("暂时无法定位", "可以手动设置位置，或直接搜索线路", "设置位置", actions.manualCenter)
                        locationGranted -> StatusPanel("定位未成功", state.errors["location"] ?: "请检查定位开关和网络后重试", "重新定位", actions.locate)
                        else -> StatusPanel("允许定位后查看附近站点", "也可以手动设置位置", "授权定位", actions.locate)
                    }
                    if (BuildConfig.AMAP_CONFIGURED) TextButton(actions.manualCenter, Modifier.fillMaxWidth()) { Text("手动设置位置") }
                }
                item(key = "error") { StaleWarning(state.errors["nearby"], { actions.network { model.loadNearby() } }) }
                if (state.searchedNearby && state.nearby.isEmpty() && state.errors["nearby"] == null && !state.loading) item {
                    StatusPanel("附近未找到公交站", "${state.settings.radius} 米范围内没有站点", "扩大到 3000 米", { actions.network { model.loadNearby(3000) } })
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
                if (state.settings.searchHistory.isEmpty()) item { StatusPanel("输入线路号或站点名称") }
            } else {
                if (!state.settings.privacyAccepted) item { StatusPanel("搜索前请先同意隐私说明", "", "同意并搜索", { actions.network { model.search(query) } }) }
                if (state.searchLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在搜索…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.searchWarning != null) item { StaleWarning(state.searchWarning, { actions.network { model.search(query) } }) }
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
                                Text("开往 ${route.toStation.ifBlank { if (route.direction == "1") "上行" else "下行" }}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(route.fromStation.takeIf(String::isNotBlank)?.let { "$it 发车" }, route.stationName.takeIf(String::isNotBlank)?.let { "途经 $it" })
                                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (shownStations.isNotEmpty()) item { Text("站点", style = MaterialTheme.typography.titleMedium) }
                items(shownStations, key = { "station:${it.key}" }) { group ->
                    BusCard(Modifier.clickable { model.rememberSearch(query); actions.station(group) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Signpost, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(group.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(group.distanceMeters?.let(::formatDistance), group.platforms.maxOfOrNull { it.routeCount }?.takeIf { it > 0 }?.let { "${it}条线路" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!state.searchLoading && shownRoutes.isEmpty() && shownStations.isEmpty() && state.searchWarning == null && state.settings.privacyAccepted) item { StatusPanel("没有找到「$query」相关的线路或站点", "换个关键词试试") }
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