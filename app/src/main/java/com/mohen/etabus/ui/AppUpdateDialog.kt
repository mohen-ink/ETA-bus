package com.mohen.etabus.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohen.etabus.update.AppUpdateViewModel
import com.mohen.etabus.update.GitHubReleaseClient
import com.mohen.etabus.update.UpdateInstaller
import com.mohen.etabus.update.UpdatePhase
import java.util.Locale

@Composable
internal fun AppUpdateDialog(model: AppUpdateViewModel, currentVersion: String, network: (() -> Unit) -> Unit) {
    val state by model.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingInstall by rememberSaveable { mutableStateOf(false) }
    var linkError by remember(state.phase) { mutableStateOf<String?>(null) }
    val install: () -> Unit = {
        val file = model.ui.value.file
        if (file == null) model.installError("安装包缓存已失效，请重新下载")
        else try {
            model.clearInstallError()
            context.startActivity(UpdateInstaller.installIntent(context, file))
        } catch (_: ActivityNotFoundException) {
            model.installError("未找到系统安装器，请通过Release页面手动安装")
        } catch (_: SecurityException) {
            model.installError("系统禁止安装，请确认已允许此来源安装应用")
        } catch (error: Exception) {
            model.installError(error.message ?: "无法启动系统安装器")
        }
    }
    val currentInstall by rememberUpdatedState(install)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val requested = pendingInstall
        pendingInstall = false
        if (requested && model.ui.value.phase == UpdatePhase.READY) {
            if (UpdateInstaller.canInstall(context)) currentInstall()
            else model.installError("尚未允许此来源安装应用，安装包已保留，可再次点击安装")
        }
    }
    if (!state.dialogVisible || state.phase == UpdatePhase.IDLE) return
    val release = state.release
    val asset = release?.asset
    val downloading = state.phase == UpdatePhase.DOWNLOADING
    val verifying = downloading && asset != null && state.downloadedBytes >= asset.size
    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text(when (state.phase) {
            UpdatePhase.CHECKING -> "正在检查更新"
            UpdatePhase.AVAILABLE -> "发现新版本 ${release?.tag.orEmpty()}"
            UpdatePhase.CURRENT -> "无需更新"
            UpdatePhase.DOWNLOADING -> if (verifying) "正在校验安装包" else "正在下载更新"
            UpdatePhase.READY -> "更新已下载"
            UpdatePhase.FAILED -> "更新未完成"
            UpdatePhase.IDLE -> "检查更新"
        }) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本：$currentVersion", style = MaterialTheme.typography.bodySmall)
                if (release != null) {
                    Text("最新正式版：${release.tag}")
                    if (release.publishedAt.isNotBlank()) Text("发布时间：${release.publishedAt.substringBefore('T')}", style = MaterialTheme.typography.bodySmall)
                    if (asset != null) Text("安装包：${formatUpdateBytes(asset.size)}", style = MaterialTheme.typography.bodySmall)
                }
                when (state.phase) {
                    UpdatePhase.CHECKING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在连接GitHub获取最新正式版…") }
                    UpdatePhase.AVAILABLE -> {
                        if (asset == null) Text("此版本没有适合本机CPU架构的APK，请打开Release页面查看。", color = MaterialTheme.colorScheme.error)
                        else Text("下载完成后将由系统确认安装，保留原应用数据。")
                    }
                    UpdatePhase.CURRENT -> Text("当前版本无需更新。")
                    UpdatePhase.DOWNLOADING -> {
                        val fraction = if (asset == null) 0f else (state.downloadedBytes.toDouble() / asset.size).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(fraction * 100).toInt()}% · ${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(asset?.size ?: 0)}")
                        if (verifying) Text("正在核对文件完整性、应用签名和版本，请稍候…")
                    }
                    UpdatePhase.READY -> Text("安装包校验通过。点击安装后按系统提示操作；如尚未授权，需先允许此来源安装应用。")
                    else -> Unit
                }
                if (state.error != null) Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (linkError != null) Text(linkError.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (release != null && release.notes.isNotBlank() && state.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)) {
                    Text("更新说明", style = MaterialTheme.typography.titleSmall)
                    Text(release.notes, style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ network {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release?.pageUrl ?: GitHubReleaseClient.RELEASES_URL))) }
                    catch (_: ActivityNotFoundException) { linkError = "未找到可打开GitHub的浏览器" }
                    catch (_: SecurityException) { linkError = "系统不允许打开GitHub链接" }
                } }) { Text("打开Release页面") }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> if (asset != null) TextButton({ network { model.download() } }) { Text("下载更新") }
                    else TextButton(model::dismiss) { Text("知道了") }
                UpdatePhase.READY -> TextButton({
                    if (UpdateInstaller.canInstall(context)) install()
                    else try {
                        pendingInstall = true
                        permission.launch(UpdateInstaller.permissionIntent(context))
                    } catch (_: Exception) {
                        pendingInstall = false
                        model.installError("无法打开安装授权设置，请在系统设置中允许此来源安装应用")
                    }
                }, enabled = !pendingInstall) { Text("安装更新") }
                UpdatePhase.FAILED -> TextButton({ network { model.retry() } }) {
                    Text(if (release?.asset != null) "重试下载" else "重新检查")
                }
                UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING -> TextButton(model::dismiss) { Text("稍后查看") }
                else -> TextButton(model::dismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            if (downloading) TextButton(model::cancelDownload) { Text("取消下载") }
            else if (state.phase in listOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.FAILED)) {
                TextButton(model::dismiss) { Text("稍后") }
            }
        }
    )
}

internal fun formatUpdateBytes(bytes: Long): String = String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))