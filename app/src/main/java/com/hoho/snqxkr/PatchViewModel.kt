package com.hoho.snqxkr

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PatchStatus {
    UNKNOWN,        // 아직 확인 못함 (Shizuku 미연결 등)
    NO_GAME,        // 게임 미설치
    NOT_PATCHED,    // 대상 파일 없음
    PATCHED_LATEST, // 적용됨 + 최신 한패
    PATCHED_OLD,    // 적용됨 + 서버에 새 한패 있음
    FOREIGN         // 내가 넣지 않은 파일이 들어 있음
}

data class UiState(
    val gamePkg: String? = null,
    val gameLabel: String = "",
    val gameVersion: String = "",
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    val privilegedUid: Int = -1,
    val status: PatchStatus = PatchStatus.UNKNOWN,
    val targetSize: Long = -1,
    val targetSha: String = "",
    val targetTime: Long = 0,
    val cacheSize: Long = -1,
    val cacheSha: String = "",
    val checkedAt: Long = 0,
    val appliedAt: Long = 0,
    val hasBackup: Boolean = false,
    val gameRunning: Boolean = false,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val progress: Float = -1f,
    val progressText: String = "",
    val log: List<String> = emptyList(),
    val snack: String? = null,
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PatchRepository(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val patchUrl: String get() = repo.url

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private fun log(msg: String) = _ui.update {
        it.copy(log = (it.log + (clock.format(Date()) + "  " + msg)).takeLast(200))
    }

    fun dismissSnack() = _ui.update { it.copy(snack = null) }

    init {
        ShizukuBridge.init()
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val found = Paths.GAME_PACKAGES.firstNotNullOfOrNull { g ->
            runCatching { pm.getPackageInfo(g.pkg, 0) }.getOrNull()?.let { g to it }
        }
        val shizuku = ShizukuBridge.state(app)

        _ui.update {
            it.copy(
                gamePkg = found?.first?.pkg,
                gameLabel = found?.first?.label.orEmpty(),
                gameVersion = found?.second?.versionName.orEmpty(),
                shizuku = shizuku,
                cacheSize = if (repo.cacheFile.isFile) repo.cacheFile.length() else -1,
                cacheSha = repo.cachedSha,
                checkedAt = repo.checkedAt,
                appliedAt = repo.appliedAt,
                hasBackup = repo.backupFile.isFile,
                status = if (found == null) PatchStatus.NO_GAME else it.status,
            )
        }
        if (found == null) {
            log("소전2 중섭 클라이언트를 찾지 못했습니다")
            return@launch
        }
        if (shizuku == ShizukuState.READY) inspectTarget()
    }

    fun requestShizukuPermission() = viewModelScope.launch {
        val granted = ShizukuBridge.requestPermission()
        log(if (granted) "Shizuku 권한 승인됨" else "Shizuku 권한 거부됨")
        refresh()
    }

    private suspend fun service(): IFileService? {
        ShizukuBridge.service?.let { return it }
        val r = ShizukuBridge.bind()
        r.exceptionOrNull()?.let { log("Shizuku 연결 실패: " + it.message) }
        return r.getOrNull()
    }

    /** 게임 폴더의 현재 상태를 읽어 status 를 갱신 */
    fun inspectTarget() = viewModelScope.launch { inspectTargetInternal() }

    private suspend fun inspectTargetInternal() {
        val pkg = _ui.value.gamePkg ?: return
        val svc = service() ?: run {
            _ui.update { it.copy(status = PatchStatus.UNKNOWN) }
            return
        }
        val path = Paths.targetFile(pkg)
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val exists = svc.exists(path)
                Triple(
                    exists,
                    if (exists) svc.sha256(path).orEmpty() else "",
                    if (exists) svc.size(path) else -1L
                ) to (svc.lastModified(path) to svc.isRunning(pkg))
            }.getOrNull()
        }
        if (info == null) {
            log("게임 폴더 확인 실패")
            return
        }
        val (triple, extra) = info
        val (exists, sha, size) = triple
        val uid = runCatching { svc.selfUid() }.getOrDefault(-1)

        val status = when {
            !exists -> PatchStatus.NOT_PATCHED
            sha.isNotEmpty() && sha == repo.cachedSha -> PatchStatus.PATCHED_LATEST
            sha.isNotEmpty() && sha == repo.appliedSha -> PatchStatus.PATCHED_OLD
            else -> PatchStatus.FOREIGN
        }
        _ui.update {
            it.copy(
                status = status,
                targetSize = size,
                targetSha = sha,
                targetTime = extra.first,
                gameRunning = extra.second,
                privilegedUid = uid,
            )
        }
    }

    /** 업데이트 확인만 (다운로드는 변경 시에만) */
    fun checkUpdate() = launchTask(busyLabel = "업데이트 확인 중") {
        when (val r = doSync()) {
            is SyncResult.UpToDate -> log("최신 상태 (" + r.via + ") · " + human(r.size))
            is SyncResult.Downloaded -> log("새 한패 다운로드 완료 · " + human(r.size))
            is SyncResult.Failed -> log("확인 실패: " + r.message)
        }
        inspectTargetInternal()
    }

    /** 다운로드(필요 시) 후 게임 폴더에 적용 */
    fun downloadAndApply() = launchTask(busyLabel = "패치 적용 중") {
        val pkg = _ui.value.gamePkg ?: run { log("게임이 설치돼 있지 않습니다"); return@launchTask }
        val svc = service() ?: run { log("Shizuku 연결이 필요합니다"); return@launchTask }

        if (withContext(Dispatchers.IO) { runCatching { svc.isRunning(pkg) }.getOrDefault(false) }) {
            _ui.update { it.copy(gameRunning = true, snack = "게임이 실행 중입니다. 완전히 종료한 뒤 적용하세요") }
            log("게임 실행 중 → 적용 중단")
            return@launchTask
        }

        when (val r = doSync()) {
            is SyncResult.Failed -> {
                log("다운로드 실패: " + r.message)
                if (!repo.cacheFile.isFile) {
                    _ui.update { it.copy(snack = "다운로드 실패: " + r.message) }
                    return@launchTask
                }
                log("캐시된 한패로 계속 진행")
            }
            is SyncResult.UpToDate -> log("서버 파일 그대로 (" + r.via + ") · 다운로드 생략")
            is SyncResult.Downloaded -> log("새 한패 받음 · " + human(r.size))
        }

        val src = repo.cacheFile
        if (!src.isFile) { log("한패 파일이 없습니다"); return@launchTask }
        val srcSha = repo.cachedSha
        val dst = Paths.targetFile(pkg)

        _ui.update { it.copy(busyLabel = "게임 폴더에 복사 중", progress = -1f, progressText = "") }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val exists = svc.exists(dst)
                val curSha = if (exists) svc.sha256(dst).orEmpty() else ""
                if (exists && curSha == srcSha) return@runCatching "SAME"

                // 원본(혹은 이전 파일) 백업은 최초 1회만
                if (exists && !repo.backupFile.isFile) {
                    repo.backupFile.parentFile?.mkdirs()
                    val err = svc.copyFile(dst, repo.backupFile.absolutePath)
                    if (err != null) return@runCatching "BACKUP_FAIL:" + err
                }
                val err = svc.copyFile(src.absolutePath, dst)
                if (err != null) return@runCatching "COPY_FAIL:" + err
                val after = svc.sha256(dst).orEmpty()
                if (after != srcSha) return@runCatching "VERIFY_FAIL:" + after
                "OK"
            }.getOrElse { "EX:" + it.message }
        }

        when {
            result == "SAME" -> {
                repo.appliedSha = srcSha
                log("이미 최신 한패가 적용돼 있습니다 · 복사 생략")
                _ui.update { it.copy(snack = "이미 최신 한글패치가 적용돼 있습니다") }
            }
            result == "OK" -> {
                repo.appliedSha = srcSha
                repo.appliedAt = System.currentTimeMillis()
                log("적용 완료 · " + human(src.length()))
                _ui.update { it.copy(snack = "한글패치 적용 완료", appliedAt = repo.appliedAt) }
            }
            result.startsWith("BACKUP_FAIL") -> {
                log("백업 실패 → 중단 · " + result.substringAfter(':'))
                _ui.update { it.copy(snack = "백업 실패로 중단했습니다") }
            }
            else -> {
                log("적용 실패 · " + result)
                _ui.update { it.copy(snack = "적용 실패: " + result) }
            }
        }
        inspectTargetInternal()
    }

    fun restoreBackup() = launchTask(busyLabel = "원본 복원 중") {
        val pkg = _ui.value.gamePkg ?: return@launchTask
        val svc = service() ?: return@launchTask
        if (!repo.backupFile.isFile) { log("백업 파일이 없습니다"); return@launchTask }
        val err = withContext(Dispatchers.IO) {
            svc.copyFile(repo.backupFile.absolutePath, Paths.targetFile(pkg))
        }
        if (err == null) {
            repo.appliedSha = ""
            log("원본 복원 완료")
            _ui.update { it.copy(snack = "원본 파일로 되돌렸습니다") }
        } else {
            log("복원 실패 · " + err)
        }
        inspectTargetInternal()
    }

    fun removePatch() = launchTask(busyLabel = "패치 제거 중") {
        val pkg = _ui.value.gamePkg ?: return@launchTask
        val svc = service() ?: return@launchTask
        val err = withContext(Dispatchers.IO) { svc.deleteFile(Paths.targetFile(pkg)) }
        if (err == null) {
            repo.appliedSha = ""
            log("패치 파일 삭제 완료 (게임이 다시 중국어로 돌아갑니다)")
            _ui.update { it.copy(snack = "패치 파일을 삭제했습니다") }
        } else {
            log("삭제 실패 · " + err)
        }
        inspectTargetInternal()
    }

    fun clearCache() = launchTask(busyLabel = "캐시 정리 중") {
        withContext(Dispatchers.IO) { repo.cacheFile.delete() }
        repo.cachedSha = ""
        repo.etag = ""
        log("다운로드 캐시를 비웠습니다")
        _ui.update { it.copy(cacheSize = -1, cacheSha = "") }
    }

    private suspend fun doSync(): SyncResult {
        _ui.update { it.copy(busyLabel = "한패 업데이트 확인 중", progress = -1f) }
        val r = repo.sync { done, total ->
            _ui.update {
                it.copy(
                    busyLabel = "한패 다운로드 중",
                    progress = if (total > 0) done.toFloat() / total else -1f,
                    progressText = human(done) + " / " + (if (total > 0) human(total) else "?")
                )
            }
        }
        _ui.update {
            it.copy(
                progress = -1f,
                progressText = "",
                cacheSize = if (repo.cacheFile.isFile) repo.cacheFile.length() else -1,
                cacheSha = repo.cachedSha,
                checkedAt = repo.checkedAt,
            )
        }
        return r
    }

    private fun launchTask(busyLabel: String, block: suspend () -> Unit) = viewModelScope.launch {
        if (_ui.value.busy) return@launch
        _ui.update { it.copy(busy = true, busyLabel = busyLabel) }
        try {
            block()
        } catch (t: Throwable) {
            log("오류: " + t)
        } finally {
            _ui.update {
                it.copy(
                    busy = false,
                    busyLabel = "",
                    progress = -1f,
                    progressText = "",
                    hasBackup = repo.backupFile.isFile
                )
            }
        }
    }

    companion object {
        fun human(bytes: Long): String = when {
            bytes < 0 -> "-"
            bytes < 1024 -> bytes.toString() + " B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }

        fun time(ms: Long): String =
            if (ms <= 0) "없음"
            else SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(ms))
    }
}
