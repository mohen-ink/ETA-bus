package com.mohen.etabus.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): AppVersion = AppVersion.parse(
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    ) ?: throw IOException("无法识别当前应用版本，请通过Release页面查看更新")

    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, release: GitHubRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("下载的文件不是有效安装包")
        if (archive.packageName != context.packageName) throw IOException("安装包不属于秒查公交，已拒绝安装")
        if (AppVersion.parse(archive.versionName.orEmpty()) != release.version) throw IOException("安装包版本与Release不一致")
        val currentVersion = AppVersion.parse(installed.versionName.orEmpty()) ?: throw IOException("无法识别当前应用版本")
        if (release.version <= currentVersion || versionCode(archive) <= versionCode(installed)) {
            throw IOException("安装包版本不高于当前版本，已阻止降级或重复安装")
        }
        val installedSigners = signerDigests(installed)
        if (installedSigners.isEmpty() || installedSigners != signerDigests(archive)) {
            throw IOException("安装包签名与当前应用不一致，请从原安装渠道更新")
        }
        if ((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) {
            throw IOException("当前Android版本不支持此安装包")
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")
    )

    fun installIntent(context: Context, file: File): Intent {
        val directory = File(context.cacheDir, "updates").canonicalPath + File.separator
        if (!file.isFile || !file.canonicalPath.startsWith(directory)) throw IOException("安装包缓存已失效，请重新下载")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }
}