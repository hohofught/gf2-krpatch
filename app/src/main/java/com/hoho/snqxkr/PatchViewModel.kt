package com.hoho.snqxkr

import android.app.Application
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

data class UiState(
    val installed: List<GamePkg> = emptyList(),  // 설치된 중섭 클라이언트 전부
    val gamePkg: String? = null,                  // 그중 지금 다루는 것
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
    /** 받아 둔 한패가 지금 게임 버전용인지. 모르면 null */
    val patchMatchesGame: Boolean? = null,
    /** 지금 게임 버전으로 임시 복구본을 만들 수 있는지 */
    val canRepair: Boolean = false,
    val repairedAt: Long = 0,
    val repairedCoverage: Float = -1f,
    val memorySize: Int = 0,
    val memoryAt: Long = 0,
    /** 1.0 이 남긴 원본 백업으로 번역 메모리를 준비할 수 있음 */
    val canBootstrap: Boolean = false,
    val autoCheck: Boolean = false,
    val checkIntervalHours: Int = 12,
    val allowMobileData: Boolean = false,
    val notifyUnpatched: Boolean = true,
    val notifyOfficial: Boolean = true,
    val notifyUpdate: Boolean = false,
    val remotePending: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val lastCheckAt: Long = 0,
    val lastCheckNote: String = "",
    val busy: Boolean = false,
    val busyLabel: String = "",
    val progress: Float = -1f,
    val progressText: String = "",
    val log: List<String> = emptyList(),
    val snack: String? = null,
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = PatchEngine(app)
    private val repo = engine.repo
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val patchUrl: String get() = repo.url

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    private fun log(msg: String) = _ui.update {
        it.copy(log = (it.log + (clock.format(Date()) + "  " + msg)).takeLast(200))
    }

    fun dismissSnack() = _ui.update { it.copy(snack = null) }

    init {
        // Shizuku 가 켜지거나 꺼질 때, 권한 결과가 올 때 화면을 다시 맞춘다
        viewModelScope.launch { ShizukuBridge.changes.collect { refresh() } }
        PatchCheckWorker.schedule(app, repo.autoCheck, repo.checkIntervalHours)
        refresh()
    }

    private var migrated = false

    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val installed = Paths.GAME_PACKAGES.mapNotNull { g ->
            runCatching { pm.getPackageInfo(g.pkg, 0) }.getOrNull()?.let { g to it }
        }
        if (!migrated && installed.isNotEmpty()) {
            repo.migrateLegacy(installed.first().first.pkg)
            migrated = true
        }
        val chosen = installed.firstOrNull { it.first.pkg == repo.selectedPkg } ?: installed.firstOrNull()
        val shizuku = ShizukuBridge.state(app)

        _ui.update {
            val switched = it.gamePkg != chosen?.first?.pkg
            it.copy(
                installed = installed.map { p -> p.first },
                gamePkg = chosen?.first?.pkg,
                gameLabel = chosen?.first?.label.orEmpty(),
                gameVersion = chosen?.second?.versionName.orEmpty(),
                shizuku = shizuku,
                status = when {
                    chosen == null -> PatchStatus.NO_GAME
                    switched -> PatchStatus.UNKNOWN
                    else -> it.status
                },
                targetSize = if (switched) -1 else it.targetSize,
                targetSha = if (switched) "" else it.targetSha,
            ).withStored()
        }
        if (chosen == null) {
            log("소전2 중섭 클라이언트를 찾지 못했습니다")
            return@launch
        }
        if (shizuku == ShizukuState.READY) inspectTarget()
    }

    /** 저장된 기록·설정을 화면 상태에 옮긴다 */
    private fun UiState.withStored(): UiState {
        val t = gamePkg?.let { repo.target(it) }
        return copy(
            cacheSize = if (repo.cacheFile.isFile) repo.cacheFile.length() else -1,
            cacheSha = repo.cachedSha,
            checkedAt = repo.checkedAt,
            appliedAt = t?.appliedAt ?: 0L,
            hasBackup = t?.backupFile?.isFile == true,
            repairedAt = t?.repairedAt ?: 0L,
            repairedCoverage = t?.repairedCoverage ?: -1f,
            memorySize = if (repo.memoryFile.isFile) repo.memorySize else 0,
            memoryAt = repo.memoryAt,
            canBootstrap = engine.canBootstrap(),
            autoCheck = repo.autoCheck,
            checkIntervalHours = repo.checkIntervalHours,
            allowMobileData = repo.allowMobileData,
            notifyUnpatched = repo.notifyUnpatched,
            notifyOfficial = repo.notifyOfficial,
            notifyUpdate = repo.notifyUpdate,
            remotePending = repo.remotePending,
            notificationsAllowed = Notices.permitted(getApplication()),
            lastCheckAt = repo.lastCheckAt,
            lastCheckNote = repo.lastCheckNote,
        )
    }

    /** 官服·B服·QQ 중 다룰 클라이언트 고르기 (알림을 탭해서 들어온 경우 포함) */
    fun selectGame(pkg: String) {
        if (Paths.GAME_PACKAGES.none { it.pkg == pkg }) return
        if (_ui.value.busy || pkg == _ui.value.gamePkg) return
        repo.selectedPkg = pkg
        refresh()
    }

    fun requestShizukuPermission() = viewModelScope.launch {
        val granted = ShizukuBridge.requestPermission()
        val state = ShizukuBridge.state(getApplication())
        log(
            when {
                granted -> "Shizuku 권한 승인됨"
                state == ShizukuState.DENIED -> "Shizuku 권한이 '다시 묻지 않음'으로 거부돼 있습니다. Shizuku 앱에서 허용하세요"
                else -> "Shizuku 권한 거부됨"
            }
        )
        refresh()
    }

    /** 끄면 예약 작업 자체를 지운다 */
    fun setAutoCheck(enabled: Boolean) {
        repo.autoCheck = enabled
        PatchCheckWorker.schedule(getApplication(), enabled, repo.checkIntervalHours)
        _ui.update { it.withStored() }
    }

    fun setCheckInterval(hours: Int) {
        repo.checkIntervalHours = hours
        PatchCheckWorker.schedule(getApplication(), repo.autoCheck, hours)
        _ui.update { it.withStored() }
    }

    fun setAllowMobileData(enabled: Boolean) {
        repo.allowMobileData = enabled
        _ui.update { it.withStored() }
    }

    fun setNotifyUnpatched(enabled: Boolean) { repo.notifyUnpatched = enabled; _ui.update { it.withStored() } }
    fun setNotifyOfficial(enabled: Boolean) { repo.notifyOfficial = enabled; _ui.update { it.withStored() } }
    fun setNotifyUpdate(enabled: Boolean) { repo.notifyUpdate = enabled; _ui.update { it.withStored() } }

    fun onNotificationPermission() = _ui.update { it.withStored() }

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
        val info = engine.inspect(svc, pkg, ::log) ?: return
        val patchLayout = engine.patchLayout()
        _ui.update {
            it.copy(
                status = info.status,
                targetSize = info.size,
                targetSha = info.sha,
                targetTime = info.modified,
                gameRunning = info.running,
                privilegedUid = info.uid,
                patchMatchesGame = if (info.gameLayout.isEmpty() || patchLayout.isEmpty()) null
                else info.gameLayout == patchLayout,
                canRepair = engine.canRepair(pkg, info),
            ).withStored()
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

    /** 다운로드(필요 시) 후 게임 폴더에 적용. 게임 버전과 안 맞는 한패는 넣지 않는다. */
    fun downloadAndApply() = launchTask(busyLabel = "패치 적용 중") {
        val pkg = _ui.value.gamePkg ?: run { log("게임이 설치돼 있지 않습니다"); return@launchTask }
        val svc = service() ?: run { log("Shizuku 연결이 필요합니다"); return@launchTask }

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

        _ui.update { it.copy(busyLabel = "게임 폴더에 복사 중", progress = -1f, progressText = "") }
        val snack = when (val r = engine.apply(svc, pkg, ::log)) {
            ApplyOutcome.Applied -> { log("적용 완료"); "한글패치 적용 완료" }
            ApplyOutcome.Same -> { log("이미 최신 한패가 적용돼 있습니다 · 복사 생략"); "이미 최신 한글패치가 적용돼 있습니다" }
            ApplyOutcome.GameRunning -> {
                _ui.update { it.copy(gameRunning = true) }
                log("게임 실행 중 → 적용 중단")
                "게임이 실행 중입니다. 완전히 종료한 뒤 적용하세요"
            }
            ApplyOutcome.NoPatch -> { log("한패 파일이 없습니다"); "한패 파일이 없습니다" }
            ApplyOutcome.VersionMismatch -> {
                log("한패가 아직 이 게임 버전용이 아닙니다 → 적용 중단")
                "이 한패는 아직 새 게임 버전용이 아닙니다. 임시 복구를 쓰세요"
            }
            is ApplyOutcome.Failed -> { log("적용 실패 · " + r.message); "적용 실패: " + r.message }
        }
        _ui.update { it.copy(snack = snack) }
        inspectTargetInternal()
    }

    /** 게임 업데이트로 한패가 안 맞을 때, 번역 메모리로 새 공식 원본을 한국어화해서 넣는다 */
    fun repair() = launchTask(busyLabel = "임시 복구 중") {
        val pkg = _ui.value.gamePkg ?: return@launchTask
        val svc = service() ?: run { log("Shizuku 연결이 필요합니다"); return@launchTask }
        _ui.update { it.copy(busyLabel = "새 공식 원본을 한국어로 바꾸는 중", progress = -1f) }
        val snack = when (val r = engine.repair(svc, pkg, ::log)) {
            is RepairOutcome.Done -> {
                val pct = percent(r.coverage)
                log("임시 복구 완료 · 한국어 $pct (${r.translated}줄), 중국어로 남음 ${r.leftChinese}줄")
                "임시 복구 완료 · 한국어 $pct"
            }
            RepairOutcome.GameRunning -> "게임이 실행 중입니다. 완전히 종료한 뒤 복구하세요"
            RepairOutcome.NoOfficial -> "이 게임 버전의 공식 원본을 아직 보관하지 못했습니다"
            RepairOutcome.NoMemory -> "번역 메모리가 없습니다"
            is RepairOutcome.Failed -> { log("복구 실패 · " + r.message); "복구 실패: " + r.message }
        }
        _ui.update { it.copy(snack = snack) }
        inspectTargetInternal()
    }

    /** 번역 메모리 준비: 1.0 이 남긴 원본 백업 + GitHub 이력의 같은 버전 한패 */
    fun prepareMemory() = launchTask(busyLabel = "번역 메모리 준비 중") {
        val ok = engine.bootstrapMemory(::log)
        _ui.update { it.copy(snack = if (ok) "번역 메모리 준비 완료" else "번역 메모리를 준비하지 못했습니다") }
        inspectTargetInternal()
    }

    fun restoreBackup() = launchTask(busyLabel = "원본 복원 중") {
        val pkg = _ui.value.gamePkg ?: return@launchTask
        val svc = service() ?: return@launchTask
        val snack = when (val r = engine.restoreBackup(svc, pkg, ::log)) {
            RestoreOutcome.Restored -> { log("원본 복원 완료"); "원본 파일로 되돌렸습니다" }
            RestoreOutcome.NoBackup -> "백업 파일이 없습니다"
            RestoreOutcome.GameRunning -> "게임이 실행 중입니다. 완전히 종료한 뒤 복원하세요"
            RestoreOutcome.OldVersion -> {
                log("백업은 이전 게임 버전의 원본이라 복원하지 않았습니다")
                "백업이 이전 게임 버전의 원본이라 되돌리면 문장이 엉뚱해집니다. 복원하지 않았습니다"
            }
            is RestoreOutcome.Failed -> { log("복원 실패 · " + r.message); "복원 실패: " + r.message }
        }
        _ui.update { it.copy(snack = snack) }
        inspectTargetInternal()
    }

    fun removePatch() = launchTask(busyLabel = "패치 제거 중") {
        val pkg = _ui.value.gamePkg ?: return@launchTask
        val svc = service() ?: return@launchTask
        val err = withContext(Dispatchers.IO) { svc.deleteFile(Paths.targetFile(pkg)) }
        if (err == null) {
            repo.target(pkg).appliedSha = ""
            repo.target(pkg).repairedSha = ""
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
        val r = engine.sync { done, total ->
            _ui.update {
                it.copy(
                    busyLabel = "한패 다운로드 중",
                    progress = if (total > 0) done.toFloat() / total else -1f,
                    progressText = human(done) + " / " + (if (total > 0) human(total) else "?")
                )
            }
        }
        _ui.update { it.copy(progress = -1f, progressText = "").withStored() }
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
            _ui.update { it.copy(busy = false, busyLabel = "", progress = -1f, progressText = "").withStored() }
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

        fun percent(ratio: Double): String = String.format(Locale.US, "%.1f%%", ratio * 100)

        fun time(ms: Long): String =
            if (ms <= 0) "없음"
            else SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(ms))
    }
}
