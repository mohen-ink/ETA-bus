@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mohen.etabus.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.mohen.etabus.domain.EtaPresenter
import com.mohen.etabus.model.*
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

internal fun routeColor(name: String, explicit: String = ""): Color {
    if (explicit.isNotBlank()) {
        try { return Color(android.graphics.Color.parseColor(explicit)) }
        catch (_: IllegalArgumentException) { /* Invalid backend color uses the documented stable palette. */ }
    }
    val palette = longArrayOf(0xFF2563EB, 0xFF0D9488, 0xFF7C3AED, 0xFFDB2777, 0xFFEA580C, 0xFF059669, 0xFF4F46E5, 0xFFB45309)
    return Color(palette[(normalizeRoute(name).hashCode().toLong().absoluteValue % palette.size).toInt()])
}

@Composable
fun RouteBadge(name: String, color: String = "", modifier: Modifier = Modifier) {
    Box(modifier.defaultMinSize(minWidth = 44.dp).clip(RoundedCornerShape(8.dp))
        .background(routeColor(name, color)).padding(horizontal = 9.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(normalizeRoute(name), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LivePulse(active: Boolean, modifier: Modifier = Modifier, color: Color = LiveAmber) {
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "live")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Restart), label = "pulse")
        value
    } else 0f
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(18.dp).semantics { contentDescription = if (active) "实时信号" else "非实时数据" }) {
        val r = size.minDimension / 2
        if (active) drawCircle(color.copy(alpha = (1 - phase) * .45f), radius = r * (.4f + .6f * phase))
        drawCircle(if (active) color else idle, radius = r * .3f)
    }
}

private val dotGlyphs = mapOf(
    '0' to listOf("01110","10001","10011","10101","11001","10001","01110"),
    '1' to listOf("00100","01100","00100","00100","00100","00100","01110"),
    '2' to listOf("01110","10001","00001","00010","00100","01000","11111"),
    '3' to listOf("11110","00001","00001","01110","00001","00001","11110"),
    '4' to listOf("00010","00110","01010","10010","11111","00010","00010"),
    '5' to listOf("11111","10000","10000","11110","00001","00001","11110"),
    '6' to listOf("01110","10000","10000","11110","10001","10001","01110"),
    '7' to listOf("11111","00001","00010","00100","01000","01000","01000"),
    '8' to listOf("01110","10001","10001","01110","10001","10001","01110"),
    '9' to listOf("01110","10001","10001","01111","00001","00001","01110")
)

@Composable
private fun LedTitle(text: String, color: Color) {
    if (LocalDigitFont.current == DigitFont.MONO) {
        Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, style = TextStyle(fontFeatureSettings = "tnum"))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
            text.forEach { char ->
                val glyph = dotGlyphs[char]
                if (glyph == null) Text(char.toString(), fontSize = 22.sp, color = color, fontWeight = FontWeight.Bold)
                else Canvas(Modifier.size(17.dp, 25.dp).clearAndSetSemantics { }) {
                    val cell = size.width / 6
                    glyph.forEachIndexed { y, row -> row.forEachIndexed { x, pixel ->
                        if (pixel == '1') drawCircle(color, cell * .33f, Offset((x + 1) * cell, (y + .5f) * size.height / 7))
                    } }
                }
            }
        }
    }
}

@Composable
fun EtaText(display: EtaDisplay, modifier: Modifier = Modifier) {
    val color = when (display.kind) { EtaKind.LIVE -> LiveAmber; EtaKind.ARRIVING -> ArrivingGreen; EtaKind.ALERT -> AlertOrange; EtaKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (display.live) LivePulse(true, color = color)
            AnimatedContent(display.title, transitionSpec = {
                (slideInVertically(tween(250)) { it } + fadeIn(tween(250))) togetherWith
                    (slideOutVertically(tween(250)) { -it } + fadeOut(tween(250)))
            }, label = "etaFlip") { LedTitle(it, color) }
        }
        if (display.subtitle.isNotBlank()) Text(display.subtitle, fontSize = 13.sp, color = color,
            textAlign = TextAlign.End, fontFamily = FontFamily.Monospace, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val offset by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300)), label = "shimmer")
    val base = MaterialTheme.colorScheme.outline
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(
        listOf(base.copy(alpha = .5f), base.copy(alpha = .15f), base.copy(alpha = .5f)),
        start = Offset(offset * 500, 0f), end = Offset(offset * 500 + 250, 100f))))
}

@Composable
internal fun LoadingCards() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { BusCard { Skeleton(Modifier.fillMaxWidth(.55f).height(22.dp)); Spacer(Modifier.height(16.dp)); repeat(3) { Skeleton(Modifier.fillMaxWidth().height(30.dp)); Spacer(Modifier.height(10.dp)) } } }
    }
}

@Composable
internal fun BusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shadowElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun NoticeBanner(notices: List<String>, modifier: Modifier = Modifier) {
    val messages = notices.filter(String::isNotBlank).distinct()
    if (messages.isEmpty()) return
    var index by remember(messages) { mutableIntStateOf(0) }
    var expanded by remember(messages) { mutableStateOf(false) }
    LaunchedEffect(messages, expanded) { if (!expanded) while (true) { delay(5000); index = (index + 1) % messages.size } }
    Surface(modifier.fillMaxWidth().clickable { expanded = !expanded }, shape = RoundedCornerShape(16.dp),
        color = if (MaterialTheme.colorScheme.background == Color(0xFF0E1220)) Color(0xFF2A2414) else Color(0xFFFFF7E6)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(messages[index], Modifier.weight(1f), maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Text("${index + 1}/${messages.size}  ${if (expanded) "收起" else "展开"}", Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun StatusPanel(title: String, subtitle: String = "", action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.DirectionsBus, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        if (action != null) OutlinedButton(onAction) { Text(action) }
    }
}

@Composable
internal fun StaleWarning(error: String?, retry: () -> Unit) {
    if (error == null) return
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("未更新 · $error", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(retry) { Text("重试") }
        }
    }
}

internal fun Arrival.selection(platform: Platform) = RouteSelection(routeName, direction, fromStation, toStation, platform, stationIndex)
internal fun nearestArrival(arrivals: List<Arrival>): Arrival? = arrivals.minWithOrNull(
    compareBy<Arrival> { if (it.stops == 0 && (it.tips.contains("即将到站") || it.tips.contains("已到站"))) 0 else if (it.stops > 0) 1 else 2 }
        .thenBy { it.validMinutes ?: Double.MAX_VALUE }.thenBy { it.stops.takeIf { n -> n >= 0 } ?: Int.MAX_VALUE })

@Composable
internal fun RouteRow(arrival: Arrival, onClick: () -> Unit, favorite: Boolean? = null, onFavorite: () -> Unit = {}, current: Boolean = false) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteBadge(arrival.routeName, arrival.routeColor)
            Column(Modifier.weight(1f)) {
                if (current) Text("当前线路", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text("→ ${arrival.toStation.ifBlank { "方向 ${arrival.direction}" }}", maxLines = 2, style = MaterialTheme.typography.bodySmall)
            }
            if (favorite != null) IconButton(onFavorite, Modifier.size(40.dp)) { Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "收藏线路", tint = MaterialTheme.colorScheme.primary) }
        }
        EtaText(EtaPresenter.present(arrival), Modifier.align(Alignment.End))
    }
}

@Composable
internal fun StationCard(group: StationGroup, arrivals: List<Arrival>, expanded: Boolean, autoPreview: Boolean,
    loading: Boolean, error: String?, onOpen: () -> Unit, onToggle: () -> Unit,
    onRoute: (Arrival, Platform) -> Unit, onRetry: () -> Unit) {
    BusCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Signpost, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(group.distanceMeters?.let(::formatDistance), "${group.platforms.size}个站台").joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, "站点详情")
        }
        if (expanded || autoPreview) {
            StaleWarning(error, onRetry)
            if (loading && arrivals.isEmpty()) repeat(3) { Skeleton(Modifier.fillMaxWidth().height(50.dp)) }
            else {
                val rows = if (expanded) arrivals else arrivals.take(3)
                rows.forEach { arrival ->
                    val platform = group.platforms.firstOrNull { p -> p.point == arrival.point } ?: group.platforms.firstOrNull()
                    RouteRow(arrival, { if (platform != null) onRoute(arrival, platform) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (arrivals.isEmpty() && !loading) Text(if (error == null) "暂无有效到站记录" else "暂未取得实时数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onToggle, Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (expanded) "收起" else "展开全部")
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
    }
}

@Composable
internal fun PageBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(back) { Icon(Icons.Rounded.ArrowBack, "返回") } }, actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
internal fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton({ confirm(); onDismiss() }) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}