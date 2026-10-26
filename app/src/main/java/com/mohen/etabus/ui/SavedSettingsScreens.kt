@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohen.etabus.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mohen.etabus.model.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun SwipeDelete(key: String, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) { delete(); true } else false
    })
    key(key) {
        SwipeToDismissBox(state = dismissState, enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp)).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                    Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }, content = { content() })
    }
}

@Composable
internal fun SavedSummary(item: SavedItem, onClick: () -> Unit) {
    BusCard(Modifier.clickable(onClick = onClick)) {
        val route = item.route
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (route != null) RouteBadge(route.routeName) else Icon(Icons.Rounded.Signpost, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(route?.let { "开往 ${it.toStation.ifBlank { if (it.direction == "1") "上行" else "下行" }}" } ?: item.station?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val sub = route?.let { r -> r.platform?.name?.let { "$it 上车" } } ?: item.station?.platforms?.maxOfOrNull { it.routeCount }?.takeIf { it > 0 }?.let { "${it}条线路" }
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, "查看详情", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FavoritesScreen(state: BusUiState, model: BusViewModel, actions: UiActions, snackbar: SnackbarHostState) {
    var stationTab by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val kind = if (stationTab) SavedKind.STATION else SavedKind.ROUTE
    var rows by remember { mutableStateOf(emptyList<SavedItem>()) }
    val latestRows by rememberUpdatedState(rows)
    val latestAll by rememberUpdatedState(state.favorites)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.favorites, kind, dragging) {
        if (dragging == null) rows = state.favorites.filter { it.kind == kind }
    }
    LaunchedEffect(list, kind, state.settings.privacyAccepted, state.page) {
        if (state.page != "favorites") {
            model.setVisibleFavorites(emptySet())
            return@LaunchedEffect
        }
        snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("fav:") }?.removePrefix("fav:") }.toSet() }
            .distinctUntilChanged().collect { visible ->
                model.setVisibleFavorites(visible)
                if (state.settings.privacyAccepted && visible.isNotEmpty()) model.refreshFavorites()
            }
    }
    DisposableEffect(model) { onDispose { model.setVisibleFavorites(emptySet()) } }
    fun remove(item: SavedItem) {
        model.deleteFavorite(item.id)
        scope.launch {
            val result = snackbar.showSnackbar("已取消收藏", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) model.restoreFavorite(item)
        }
    }
    fun commitOrder() {
        val iterator = latestRows.map { it.id }.iterator()
        val ordered = latestAll.map { if (it.kind == kind && iterator.hasNext()) iterator.next() else it.id }
        model.reorderFavorites(ordered)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("收藏", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (editing && selected.isNotEmpty()) TextButton({ confirmDelete = true }) { Text("删除 ${selected.size}") }
            TextButton({ editing = !editing; selected = emptySet() }) { Text(if (editing) "完成" else "编辑") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(4.dp)) {
            listOf("线路", "站点").forEachIndexed { index, label ->
                val active = stationTab == (index == 1)
                Surface(Modifier.weight(1f).clickable { stationTab = index == 1; selected = emptySet() }, shape = RoundedCornerShape(50), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(label, Modifier.padding(vertical = 10.dp), color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        if (!state.settings.privacyAccepted && state.favorites.isNotEmpty()) TextButton({ actions.network { model.refreshFavorites() } }, Modifier.fillMaxWidth()) { Text("同意隐私说明后可查看实时到站") }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) item { StatusPanel("还没有收藏${if (stationTab) "站点" else "线路"}", "在${if (stationTab) "站点" else "线路"}详情页点击右上角星标即可收藏") }
            items(rows, key = { "fav:${it.id}" }) { item ->
                val rowModifier = Modifier.graphicsLayer { if (dragging == item.id) { translationY = dragY; alpha = .85f } }
                val open = {
                    if (editing) selected = if (item.id in selected) selected - item.id else selected + item.id
                    else { item.route?.let(actions.route) ?: item.station?.let(actions.station); Unit }
                }
                SwipeDelete(item.id, { remove(item) }) {
                    BusCard(rowModifier) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (editing) Checkbox(item.id in selected, { checked -> selected = if (checked) selected + item.id else selected - item.id })
                            Column(Modifier.weight(1f).clickable(onClick = open)) {
                                val route = item.route
                                if (route != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        val arrivals = route.platform?.let { state.arrivals[it.key] }.orEmpty()
                                        val arrival = nearestArrival(arrivals.filter { normalizeRoute(it.routeName) == normalizeRoute(route.routeName) && it.direction == route.direction })
                                        RouteBadge(route.routeName, arrival?.routeColor.orEmpty())
                                        Column {
                                            Text("开往 ${route.toStation.ifBlank { if (route.direction == "1") "上行" else "下行" }}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            route.platform?.name?.let { Text("$it 上车", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Signpost, null, tint = MaterialTheme.colorScheme.primary); Text(item.station?.name.orEmpty(), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium) }
                                }
                            }
                            if (!editing) Icon(Icons.Rounded.DragHandle, "长按拖动排序", Modifier.padding(8.dp).size(28.dp).pointerInput(item.id, kind) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = item.id; dragY = 0f },
                                    onDragCancel = { commitOrder(); dragging = null; dragY = 0f },
                                    onDragEnd = { commitOrder(); dragging = null; dragY = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount.y
                                        val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "fav:${item.id}" }
                                        if (current != null) {
                                            val center = current.offset + current.size / 2f + dragY
                                            val target = list.layoutInfo.visibleItemsInfo.filter { it.key != current.key && (it.key as? String)?.startsWith("fav:") == true }
                                                .firstOrNull { center >= it.offset && center <= it.offset + it.size }
                                            if (target != null) {
                                                val from = latestRows.indexOfFirst { it.id == item.id }
                                                val targetId = (target.key as String).removePrefix("fav:")
                                                val to = latestRows.indexOfFirst { it.id == targetId }
                                                if (from >= 0 && to >= 0 && from != to) {
                                                    rows = latestRows.toMutableList().apply { add(to, removeAt(from)) }
                                                    dragY -= target.offset - current.offset
                                                }
                                            }
                                            if (center < 80 && list.canScrollBackward) scope.launch { list.scrollToItem((list.firstVisibleItemIndex - 1).coerceAtLeast(0)) }
                                            else if (center > list.layoutInfo.viewportEndOffset - 80 && list.canScrollForward) scope.launch { list.scrollToItem((list.firstVisibleItemIndex + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0))) }
                                        }
                                    })
                            })
                        }
                        val platforms = item.route?.platform?.let(::listOf) ?: item.station?.platforms.orEmpty().take(1)
                        platforms.forEach { p -> StaleWarning(state.errors[p.key], { actions.network { model.loadPlatform(p) } }) }
                        if (platforms.any { it.key in state.loadingKeys } && platforms.all { !state.arrivals.containsKey(it.key) }) Skeleton(Modifier.fillMaxWidth().height(50.dp))
                        else if (item.route != null) {
                            val route = item.route
                            val records = platforms.flatMap { state.arrivals[it.key].orEmpty() }
                            val arrival = nearestArrival(records.filter { normalizeRoute(it.routeName) == normalizeRoute(route.routeName) && it.direction == route.direction })
                            val eta = if (arrival != null) com.mohen.etabus.domain.EtaPresenter.present(arrival) else EtaDisplay("暂无数据", if (platforms.any { state.errors.containsKey(it.key) }) "未更新 · 请重试" else "暂无来车", EtaKind.IDLE)
                            EtaText(eta, Modifier.align(Alignment.End).clickable(onClick = open))
                        } else platforms.firstOrNull()?.let { p ->
                            state.arrivals[p.key].orEmpty().take(3).forEach { arrival -> RouteRow(arrival, { actions.route(arrival.selection(p)) }) }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("删除所选收藏？", "将删除当前选中的 ${selected.size} 条收藏。", { confirmDelete = false }, {
        selected.forEach { model.deleteFavorite(it) }; selected = emptySet()
    })
}

@Composable
internal fun SettingsScreen(state: BusUiState, model: BusViewModel, actions: UiActions) {
    var options by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("设置", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge) }
        item {
            SettingsGroup("常规") {
                SettingRow("当前城市", state.city.displayName, { actions.navigate("cities") })
                SettingRow("附近范围", "${state.settings.radius} 米", { options = "radius" })
                SettingRow("自动刷新", if (state.settings.refreshSeconds == 0) "关闭" else "每 ${state.settings.refreshSeconds} 秒", { options = "refresh" })
                SettingRow("手动设置位置", if (state.center == null) "未设置" else "", actions.manualCenter)
            }
        }
        item {
            SettingsGroup("外观") {
                SettingRow("深色模式", when (state.settings.theme) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }, { options = "theme" })
            }
        }
        item {
            SettingsGroup("数据") {
                SettingRow("清除浏览历史", "${state.history.size} 条", { clear = "history" })
                SettingRow("清除收藏", "${state.favorites.size} 条", { clear = "favorites" })
                SettingRow("清除搜索记录", "", { clear = "search" })
            }
        }
        item {
            SettingsGroup("关于") {
                SettingRow("作者", "mohen", null)
                Row(Modifier.fillMaxWidth().clickable { openLink(context, GITHUB_URL) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("GitHub", Modifier.weight(1f))
                    Icon(GitHubIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("mohen-ink/ETA-bus", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingRow("隐私说明", if (state.settings.privacyAccepted) "" else "未同意", actions.privacy)
                SettingRow("数据来源", "", { source = true })
                SettingRow("开源许可", "", { actions.navigate("license") })
                SettingRow("版本", version, null)
            }
        }
        item { Text("到站信息仅供参考，请以实际运营为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
    }
    val option = options
    if (option != null) {
        val labels: List<String> = when (option) {
            "radius" -> listOf("500 米", "1000 米", "3000 米")
            "refresh" -> listOf("15 秒", "30 秒", "60 秒", "关闭")
            else -> listOf("跟随系统", "浅色", "深色")
        }
        val chosen = when (option) {
            "radius" -> listOf(500, 1000, 3000).indexOf(state.settings.radius)
            "refresh" -> listOf(15, 30, 60, 0).indexOf(state.settings.refreshSeconds)
            else -> state.settings.theme.ordinal
        }
        AlertDialog(onDismissRequest = { options = null }, title = { Text(when (option) { "radius" -> "附近搜索半径"; "refresh" -> "自动刷新间隔"; else -> "深色模式" }) }, text = {
            Column { labels.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().clickable {
                    val settings = when (option) {
                        "radius" -> state.settings.copy(radius = listOf(500, 1000, 3000)[index])
                        "refresh" -> state.settings.copy(refreshSeconds = listOf(15, 30, 60, 0)[index])
                        else -> state.settings.copy(theme = ThemeMode.entries[index])
                    }
                    model.updateSettings(settings); options = null
                }, verticalAlignment = Alignment.CenterVertically) { RadioButton(chosen == index, onClick = null); Text(label, Modifier.padding(start = 8.dp)) }
            } }
        }, confirmButton = { TextButton({ options = null }) { Text("取消") } })
    }
    if (clear != null) ConfirmDialog("确认清除？", "${when (clear) { "history" -> "当前城市的浏览历史"; "favorites" -> "当前城市的全部收藏"; else -> "搜索历史" }}将被删除，此操作不可撤销。", { clear = null }, {
        when (clear) { "history" -> model.clearHistory(); "favorites" -> model.clearFavorites(); else -> model.clearSearchHistory() }
    })
    if (source) AlertDialog(onDismissRequest = { source = false }, title = { Text("数据来源说明") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("公交数据：掌上公交。各城市数据覆盖情况以实际查询结果为准。\n\n地图与定位：高德地图。\n\n实时到站：第一辆车为公交平台实时预测；第二辆车根据车辆实时位置估算，仅供参考。\n\n本地存储：设置、浏览历史和收藏仅保存在本机，按城市分别保存。")
        }
    }, confirmButton = { TextButton({ source = false }) { Text("知道了") } })
}

private const val GITHUB_URL = "https://github.com/mohen-ink/ETA-bus"

private fun openLink(context: android.content.Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch (_: android.content.ActivityNotFoundException) { }
    catch (_: SecurityException) { }
}

private val GitHubIcon: ImageVector by lazy {
    ImageVector.Builder("GitHub", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 0.3f)
            curveTo(5.37f, 0.3f, 0f, 5.67f, 0f, 12.3f)
            curveTo(0f, 17.6f, 3.44f, 22.1f, 8.2f, 23.69f)
            curveTo(8.8f, 23.8f, 9.02f, 23.43f, 9.02f, 23.11f)
            curveTo(9.02f, 22.83f, 9.01f, 22.07f, 9f, 21.07f)
            curveTo(5.66f, 21.79f, 4.96f, 19.46f, 4.96f, 19.46f)
            curveTo(4.41f, 18.07f, 3.63f, 17.7f, 3.63f, 17.7f)
            curveTo(2.54f, 16.96f, 3.71f, 16.97f, 3.71f, 16.97f)
            curveTo(4.92f, 17.06f, 5.55f, 18.21f, 5.55f, 18.21f)
            curveTo(6.62f, 20.05f, 8.36f, 19.52f, 9.04f, 19.21f)
            curveTo(9.15f, 18.44f, 9.46f, 17.91f, 9.8f, 17.61f)
            curveTo(7.14f, 17.31f, 4.34f, 16.28f, 4.34f, 11.67f)
            curveTo(4.34f, 10.36f, 4.81f, 9.29f, 5.58f, 8.45f)
            curveTo(5.45f, 8.15f, 5.04f, 6.93f, 5.69f, 5.28f)
            curveTo(5.69f, 5.28f, 6.7f, 4.96f, 8.99f, 6.51f)
            curveTo(9.95f, 6.24f, 10.98f, 6.11f, 12f, 6.11f)
            curveTo(13.02f, 6.11f, 14.05f, 6.24f, 15.01f, 6.51f)
            curveTo(17.3f, 4.96f, 18.31f, 5.28f, 18.31f, 5.28f)
            curveTo(18.96f, 6.93f, 18.55f, 8.15f, 18.43f, 8.45f)
            curveTo(19.2f, 9.29f, 19.66f, 10.36f, 19.66f, 11.67f)
            curveTo(19.66f, 16.29f, 16.85f, 17.31f, 14.18f, 17.6f)
            curveTo(14.61f, 17.97f, 15f, 18.7f, 15f, 19.82f)
            curveTo(15f, 21.42f, 14.99f, 22.72f, 14.99f, 23.11f)
            curveTo(14.99f, 23.43f, 15.2f, 23.81f, 15.81f, 23.69f)
            curveTo(20.57f, 22.1f, 24f, 17.6f, 24f, 12.3f)
            curveTo(24f, 5.67f, 18.63f, 0.3f, 12f, 0.3f)
            close()
        }
    }.build()
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BusCard { content() }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LicenseScreen(back: () -> Unit, network: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    var license by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val openUrl: (String) -> Unit = { url -> network {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: android.content.ActivityNotFoundException) { failure = "未找到可打开此链接的浏览器：${e.message.orEmpty()}" }
        catch (e: SecurityException) { failure = "系统不允许打开此链接：${e.message.orEmpty()}" }
    } }
    Column(Modifier.fillMaxSize()) {
        PageBar("开源许可证", back)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("依赖归属与许可", style = MaterialTheme.typography.titleMedium) }
            items(listOf("AndroidX / Jetpack Compose / Material / Navigation / Lifecycle" to "The Android Open Source Project · Apache License 2.0", "Kotlin / kotlinx.coroutines" to "JetBrains s.r.o. and contributors · Apache License 2.0", "Hilt / Dagger" to "Google, Inc. and contributors · Apache License 2.0", "OkHttp" to "Square, Inc. and contributors · Apache License 2.0")) { (name, attribution) ->
                BusCard { Text(name, style = MaterialTheme.typography.titleMedium); Text(attribution, style = MaterialTheme.typography.bodySmall); TextButton({ license = "Apache License 2.0" }) { Text("查看许可与官方原文") } }
            }
            item { BusCard { Text("高德地图与定位", style = MaterialTheme.typography.titleMedium); Text("高德 SDK 不属于上述开源组件，使用须遵循高德开放平台服务协议与隐私政策。", style = MaterialTheme.typography.bodySmall); TextButton({ openUrl("https://lbs.amap.com/pages/terms/") }) { Text("高德服务协议") } } }
        }
    }
    if (license != null) AlertDialog(onDismissRequest = { license = null }, title = { Text(license.orEmpty()) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Apache License\nVersion 2.0, January 2004\n\nLicensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in compliance with the License. You may obtain a copy of the License at\n\nhttps://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.")
    } }, confirmButton = { TextButton({ openUrl("https://www.apache.org/licenses/LICENSE-2.0") }) { Text("打开官方全文") } }, dismissButton = { TextButton({ license = null }) { Text("关闭") } })
    if (failure != null) AlertDialog(onDismissRequest = { failure = null }, title = { Text("链接打开失败") }, text = { Text(failure.orEmpty()) }, confirmButton = { TextButton({ failure = null }) { Text("知道了") } })
}