package com.mohen.etabus.update

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, READY, FAILED }

data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: GitHubRelease? = null,
    val downloadedBytes: Long = 0,
    val file: File? = null,
    val error: String? = null,
    val dialogVisible: Boolean = false
)

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GitHubReleaseClient
) : ViewModel() {
    private val mutable = MutableStateFlow(AppUpdateState())
    val ui: StateFlow<AppUpdateState> = mutable.asStateFlow()
    private var task: Job? = null
    @Volatile private var generation = 0L

    fun check() {
        val state = mutable.value
        if (task?.isActive == true || state.phase == UpdatePhase.READY && state.file?.isFile == true) {
            mutable.update { it.copy(dialogVisible = true) }
            return
        }
        val operation = ++generation
        mutable.value = AppUpdateState(phase = UpdatePhase.CHECKING, dialogVisible = true)
        task = viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { UpdateInstaller.installedVersion(context) }
                val release = client.latest(Build.SUPPORTED_ABIS.toList())
                if (generation == operation) mutable.update {
                    it.copy(phase = if (release.version > installed) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT, release = release)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == operation) mutable.update { it.copy(phase = UpdatePhase.FAILED, error = errorMessage(error)) }
            }
        }
    }

    fun download() {
        val release = mutable.value.release ?: return
        if (release.asset == null || task?.isActive == true) return
        val operation = ++generation
        mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, file = null, error = null, dialogVisible = true) }
        task = viewModelScope.launch {
            var downloaded: File? = null
            try {
                val file = client.download(release, context.cacheDir) { bytes ->
                    if (generation == operation) mutable.update { state ->
                        if (state.phase == UpdatePhase.DOWNLOADING) state.copy(downloadedBytes = bytes) else state
                    }
                }
                downloaded = file
                withContext(Dispatchers.IO) { UpdateInstaller.validate(context, file, release) }
                if (generation == operation) mutable.update { it.copy(phase = UpdatePhase.READY, file = file) }
                else file.delete()
            } catch (error: CancellationException) {
                downloaded?.delete()
                throw error
            } catch (error: Exception) {
                downloaded?.delete()
                if (generation == operation) mutable.update { it.copy(phase = UpdatePhase.FAILED, file = null, error = errorMessage(error)) }
            }
        }
    }

    fun retry() {
        if (mutable.value.release?.asset != null) download() else check()
    }

    fun cancelDownload() {
        if (mutable.value.phase != UpdatePhase.DOWNLOADING) return
        generation++
        task?.cancel()
        task = null
        mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, downloadedBytes = 0, file = null, error = null) }
    }

    fun dismiss() { mutable.update { it.copy(dialogVisible = false) } }
    fun clearInstallError() { mutable.update { it.copy(error = null) } }

    fun installError(message: String) {
        mutable.update {
            if (it.file?.isFile == true) it.copy(error = message)
            else it.copy(phase = UpdatePhase.FAILED, file = null, error = message)
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is UnknownHostException -> "无法连接GitHub，请检查网络后重试"
        is SocketTimeoutException -> "连接GitHub超时，请稍后重试"
        is SSLException -> "GitHub安全连接失败，请检查网络和系统时间"
        is IOException -> error.message ?: "更新失败，请检查网络后重试"
        else -> "无法处理更新信息，请通过Release页面查看更新"
    }

    override fun onCleared() {
        generation++
        task?.cancel()
        super.onCleared()
    }
}