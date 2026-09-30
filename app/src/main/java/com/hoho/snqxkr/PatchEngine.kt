package com.hoho.snqxkr

import android.content.Context
import com.hoho.snqxkr.langtable.Engine
import com.hoho.snqxkr.langtable.Engines
import com.hoho.snqxkr.langtable.LangTable
import com.hoho.snqxkr.langtable.PatchRepair
import com.hoho.snqxkr.langtable.TranslationMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class PatchStatus {
    UNKNOWN,        // 아직 확인 못함 (Shizuku 미연결 등)
    NO_GAME,        // 게임 미설치
    NOT_PATCHED,    // 대상 파일 없음
    OFFICIAL,       // 게임 원본(중국어) 파일. 한패 미적용이거나 게임 업데이트로 덮임
    PATCHED_LATEST, // 적용됨 + 최신 한패
    PATCHED_OLD,    // 적용됨 + 서버에 새 한패 있음
    REPAIRED,       // 번역 메모리로 만든 임시 복구본
    FOREIGN,        // 한패이긴 한데 최신인지 모르거나 다른 버전
}

/** 게임 폴더 한 곳의 상태 */
data class Inspection(
    val exists: Boolean,
    val sha: String,
    val size: Long,
    val modified: Long,
    val running: Boolean,
    val uid: Int,
    val status: PatchStatus,
    /** 게임 폴더 파일의 버전 지문 (Id 집합). 모르면 "" */
    val gameLayout: String,
    /**
     * 받아 둔 한패가 이 게임 버전용일 때, 보관한 공식 원문과 자리가 맞는지.
     * true 맞음, false 어긋남(업데이트 직후 올라온 한패가 그랬던 적이 있다), null 판단 못 함
     */
    val patchAligned: Boolean? = null,
    val alignRatio: Double = -1.0,
)

sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome
    data object Same : ApplyOutcome
    data object GameRunning : ApplyOutcome
    data object NoPatch : ApplyOutcome
    /** 한패가 아직 이 게임 버전용이 아님. 그대로 넣으면 문장이 엉뚱한 자리에 나온다. */
    data object VersionMismatch : ApplyOutcome
    /** 버전은 맞는데 번역이 엉뚱한 자리에 들어간 한패. ratio = 번역 안 된 줄 중 원문과 같은 자리 비율 */
    data class Misaligned(val ratio: Double) : ApplyOutcome
    data class Failed(val message: String) : ApplyOutcome
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome
    data object NoBackup : RestoreOutcome
    data object GameRunning : RestoreOutcome
    /** 백업이 이전 게임 버전의 원본이라 되돌리면 문장이 엉뚱한 자리에 나온다 */
    data object OldVersion : RestoreOutcome
    data class Failed(val message: String) : RestoreOutcome
}

sealed interface RepairOutcome {
    /** fromPatch: 그중 업데이트 전에 쓰던 한패에서 옮긴 줄 */
    data class Done(val coverage: Double, val translated: Int, val leftChinese: Int, val fromPatch: Int) : RepairOutcome
    data object GameRunning : RepairOutcome
    /** 지금 게임 버전의 공식 원본을 보관하지 못함 */
    data object NoOfficial : RepairOutcome
    data object NoMemory : RepairOutcome
    data class Failed(val message: String) : RepairOutcome
}

/**
 * 화면(PatchViewModel)과 백그라운드 확인(PatchCheckWorker)이 같이 쓰는 한패 로직.
 * 게임 폴더는 Shizuku 의 FileService 로만 읽고 쓴다.
 *
 * 업데이트 대응 흐름 (docs/lang-table-format.md):
 *  1) 게임 폴더에 공식 원본이 보이면 보관하고, 같은 버전 한패가 있으면 번역 메모리에 넣는다.
 *  2) 한패와 게임의 버전 지문이 다르면 옛 한패 적용을 막는다.
 *  3) 대신 보관한 새 공식 원본을 번역 메모리로 한국어화하고, 업데이트 전에 쓰던 한패의 번역도
 *     새 자리로 옮긴 임시 복구본을 넣을 수 있다.
 *  4) 버전이 맞는 한패가 나오면 평소처럼 적용한다.
 */
class PatchEngine(context: Context) {

    val repo = PatchRepository(context.applicationContext)

    private val cacheDir = context.applicationContext.cacheDir

    /** 번역 엔진 (kotlin 판은 Kotlin, native 판은 C++ 를 올려 보고 안 되면 Kotlin) */
    private val eng: Engine get() = Engines.current

    val engineName: String get() = eng.name

    suspend fun sync(onProgress: (Long, Long) -> Unit = { _, _ -> }): SyncResult = LOCK.withLock {
        repo.sync(onProgress).also { if (it !is SyncResult.Failed) repo.remotePending = false }
    }

    suspend fun inspect(svc: IFileService, pkg: String, log: (String) -> Unit = {}): Inspection? =
        LOCK.withLock { withContext(Dispatchers.IO) { inspectLocked(svc, pkg, log) } }

    suspend fun patchLayout(): String = LOCK.withLock { withContext(Dispatchers.IO) { patchLayoutLocked() } }

    /**
     * 서버에 아직 받지 않은 새 한패가 있는지만 본다 (HEAD, 본문 0바이트). 화면과 백그라운드 확인이 같이 쓴다.
     * 받아 둔 한패가 없으면 새 한패가 있는 것으로 본다. 실패하면 null 이고 전에 본 값을 그대로 둔다.
     */
    suspend fun checkRemote(): Boolean? {
        val remote = withContext(Dispatchers.IO) { repo.remoteEtag() } ?: return null
        // 그사이 sync 가 한패를 받아 etag 를 바꿨을 수 있어 판단은 LOCK 안에서 한다
        return LOCK.withLock { (repo.cachedSha.isEmpty() || remote != repo.etag).also { repo.remotePending = it } }
    }

    /** force: 자리 검사에 걸린 한패도 사용자가 원하면 넣는다 (번역 메모리에는 넣지 않는다) */
    suspend fun apply(svc: IFileService, pkg: String, log: (String) -> Unit = {}, force: Boolean = false): ApplyOutcome =
        LOCK.withLock { withContext(Dispatchers.IO) { applyLocked(svc, pkg, log, force) } }

    suspend fun repair(svc: IFileService, pkg: String, log: (String) -> Unit = {}): RepairOutcome =
        LOCK.withLock { withContext(Dispatchers.IO) { repairLocked(svc, pkg, log) } }

    suspend fun restoreBackup(svc: IFileService, pkg: String, log: (String) -> Unit = {}): RestoreOutcome =
        LOCK.withLock { withContext(Dispatchers.IO) { restoreLocked(svc, pkg, log) } }

    /** 게임 폴더의 한패 파일을 지운다 (게임이 다시 받아 중국어로 돌아간다). 못 지우면 이유, 지웠으면 null */
    suspend fun removePatch(svc: IFileService, pkg: String): String? = LOCK.withLock {
        withContext(Dispatchers.IO) {
            if (svc.isRunning(pkg)) return@withContext "게임이 실행 중입니다. 완전히 종료한 뒤 제거하세요"
            svc.deleteFile(Paths.targetFile(pkg))?.let { return@withContext it }
            val t = repo.target(pkg)
            t.appliedSha = ""
            t.repairedSha = ""
            null
        }
    }

    suspend fun bootstrapMemory(log: (String) -> Unit = {}): Boolean =
        LOCK.withLock { withContext(Dispatchers.IO) { bootstrapLocked(log) } }

    /** 지금 게임 버전으로 임시 복구를 만들 수 있는지 (공식 원본 보관 + 번역 메모리) */
    fun canRepair(pkg: String, info: Inspection): Boolean {
        val t = repo.target(pkg)
        return info.gameLayout.isNotEmpty() && t.officialFile.isFile &&
            t.officialLayout == info.gameLayout && repo.memoryFile.isFile
    }

    /** 1.0 이 남긴 원본 백업으로 번역 메모리를 준비할 수 있는지 */
    fun canBootstrap(): Boolean = !repo.memoryFile.isFile && legacyOfficial() != null

    // ---- 이하 LOCK 을 잡은 상태에서만 부른다 ----

    private fun inspectLocked(svc: IFileService, pkg: String, log: (String) -> Unit): Inspection? = runCatching {
        val path = Paths.targetFile(pkg)
        val t = repo.target(pkg)
        val running = svc.isRunning(pkg)
        val uid = runCatching { svc.selfUid() }.getOrDefault(-1)
        if (!svc.exists(path)) {
            return@runCatching Inspection(false, "", -1, 0, running, uid, PatchStatus.NOT_PATCHED, "")
        }
        // 크기·수정시각이 지난번과 같으면 해시(56MB 읽기)와 한글 검사를 다시 하지 않는다
        val size = svc.size(path)
        val modified = svc.lastModified(path)
        val stamp = "$size:$modified"
        val seen = t.seen.split('|').takeIf { it.size == 3 && it[0] == stamp }
        val sha = seen?.get(1) ?: svc.sha256(path).orEmpty()
        // -2 = 해시로 상태가 정해져 한글 검사를 건너뛴 경우 (다시 필요하면 그때 센다)
        var hangul = seen?.get(2)?.toIntOrNull()?.takeIf { it != -2 }
        fun hangul(): Int = hangul ?: svc.hangulCount(path, HANGUL_SAMPLE_BYTES).also { hangul = it }
        val status = when {
            sha.isNotEmpty() && sha == repo.cachedSha -> PatchStatus.PATCHED_LATEST
            sha.isNotEmpty() && sha == t.repairedSha -> PatchStatus.REPAIRED
            sha.isNotEmpty() && sha == t.appliedSha -> PatchStatus.PATCHED_OLD
            // 해시로 모르는 파일은 내용을 본다: 한글이 없으면 게임 원본(중국어)
            hangul() == 0 -> PatchStatus.OFFICIAL
            else -> PatchStatus.FOREIGN
        }
        if (sha.isNotEmpty()) t.seen = "$stamp|$sha|${hangul ?: -2}"
        // 보관에 실패해도 상태 확인은 계속한다. 같은 파일로 56MB 복사를 되풀이하지 않게 이 프로세스 동안 기억한다
        if (status == PatchStatus.OFFICIAL && t.officialSha != sha && sha !in captureFailed) {
            runCatching { captureOfficial(svc, pkg, path, sha, log) }
                .onFailure { captureFailed += sha; log("공식 원본 보관 실패: " + it.message) }
        }
        val layout = repo.layoutFor(sha) ?: when (status) {
            PatchStatus.PATCHED_LATEST -> patchLayoutLocked()
            else -> svc.tableLayout(path).orEmpty()
        }.also { if (it.isNotEmpty()) repo.putLayout(sha, it) }
        t.lastStatus = status.name
        // 받아 둔 한패가 이 게임 버전용이면 보관한 공식 원문과 자리가 맞는지 본다 (앱 폴더 파일만 써서 Shizuku 는 필요 없다)
        val a = if (layout.isNotEmpty() && patchLayoutLocked() == layout) alignmentLocked(t, layout, log) else null
        Inspection(true, sha, size, modified, running, uid, status, layout, a?.ok, a?.ratio ?: -1.0)
    }.onFailure { log("게임 폴더 확인 실패: " + it.message) }.getOrNull()

    /**
     * 받아 둔 한패와 이 클라이언트의 공식 원문 보관본의 자리 검사. 같은 버전 원문이 없으면 null.
     * 50MB 두 개를 읽으므로 (한패, 원문) 짝마다 한 번만 하고 결과를 기억한다.
     */
    private fun alignmentLocked(t: PatchRepository.Target, layout: String, log: (String) -> Unit): PatchRepair.Alignment? {
        if (!t.officialFile.isFile || t.officialLayout != layout || !repo.cacheFile.isFile) return null
        alignmentKnown(t)?.let { return it }
        val a = runCatching { eng.alignment(t.officialFile, repo.cacheFile) }
            .onFailure { log("자리 검사 실패: " + it.message) }.getOrNull() ?: return null
        repo.putAlignment(alignKey(t), "${a.untranslated}/${a.matching}")
        if (a.ok == false) log("받아 둔 한패가 공식 원문과 자리가 맞지 않습니다 (번역 안 된 줄 ${a.untranslated}개 중 ${percent(a.ratio)} 일치)")
        return a
    }

    private fun alignKey(t: PatchRepository.Target) = repo.cachedSha.take(16) + ":" + t.officialSha.take(16)

    private fun alignmentKnown(t: PatchRepository.Target): PatchRepair.Alignment? {
        val parts = repo.alignmentFor(alignKey(t))?.split('/') ?: return null
        val u = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return PatchRepair.Alignment(u, m)
    }

    private fun percent(r: Double) = String.format(java.util.Locale.US, "%.0f%%", r * 100)

    /**
     * Shizuku 없이, 마지막으로 확인한 게임 폴더 파일로 상태를 다시 매긴다.
     * 무선 디버깅(shell) 방식은 재부팅하면 Shizuku 가 꺼지므로, 그동안 받은 한패가
     * 지금 게임 버전용인지(정식 한패 나옴·번역 갱신)는 이걸로 판단한다. 파일이 바뀌었는지는 알 수 없다.
     */
    fun lastKnown(pkg: String): Inspection? {
        val t = repo.target(pkg)
        val parts = t.seen.split('|')
        if (parts.size != 3) return null
        val sha = parts[1]
        val status = when {
            sha == repo.cachedSha -> PatchStatus.PATCHED_LATEST
            sha == t.repairedSha -> PatchStatus.REPAIRED
            sha == t.appliedSha -> PatchStatus.PATCHED_OLD
            t.lastStatus == PatchStatus.OFFICIAL.name -> PatchStatus.OFFICIAL
            else -> PatchStatus.FOREIGN
        }
        val (size, modified) = parts[0].split(':').let { (it.getOrNull(0)?.toLongOrNull() ?: -1L) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        val layout = repo.layoutFor(sha).orEmpty()
        // 자리 검사는 기억해 둔 결과만 쓴다 (백그라운드에서 50MB 두 개를 새로 읽지 않는다)
        val a = if (layout.isNotEmpty() && repo.layoutFor(repo.cachedSha) == layout && t.officialLayout == layout) alignmentKnown(t) else null
        return Inspection(true, sha, size, modified, false, -1, status, layout, a?.ok, a?.ratio ?: -1.0)
    }

    /**
     * 게임 폴더의 공식 원본을 앱으로 가져와 둔다. 번역 메모리를 만들고, 업데이트 후 복구할 때 쓴다.
     * 옆 파일에 받아 해시·버전 지문까지 확인한 뒤 바꾼다 (실패해도 전에 보관한 원본과 기록이 어긋나지 않게).
     */
    private fun captureOfficial(svc: IFileService, pkg: String, path: String, sha: String, log: (String) -> Unit) {
        val t = repo.target(pkg)
        val dst = t.officialFile
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".new")
        val layout = try {
            svc.copyFile(path, tmp.absolutePath)?.let { log("공식 원본 보관 실패: $it"); return }
            if (repo.sha256(tmp) != sha) { log("공식 원본 보관 검증 실패"); return }
            eng.layoutKey(tmp).also {
                if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
        t.officialSha = sha
        t.officialLayout = layout
        repo.putLayout(sha, layout)
        log("게임 공식 원본 보관 (버전 지문 ${layout.substringAfter(':').take(8)})")
        // 같은 버전 한패를 이미 받아 뒀으면 바로 번역 메모리에 넣는다 (실패해도 보관은 끝났다. 적용할 때 다시 한다)
        if (repo.cacheFile.isFile && patchLayoutLocked() == layout) {
            runCatching { buildMemory(dst, repo.cacheFile, repo.cachedSha, log) }
                .onFailure { log("번역 메모리 갱신 실패: " + it.message) }
        }
    }

    /** 받아 둔 한패의 버전 지문. 캐시를 지워도 기억해 둔 값으로 답한다. */
    private fun patchLayoutLocked(): String {
        val sha = repo.cachedSha
        if (sha.isEmpty()) return ""
        repo.layoutFor(sha)?.let { return it }
        if (!repo.cacheFile.isFile) return ""
        return runCatching { eng.layoutKey(repo.cacheFile) }
            .getOrDefault("").also { if (it.isNotEmpty()) repo.putLayout(sha, it) }
    }

    /**
     * 같은 버전의 공식 원본 + 한패로 번역 메모리를 만들어 기존 것과 합친다 (새 번역이 이긴다).
     * 파일이 [TranslationMemory.MAX_BYTES] 를 넘으면 가장 오래전에 본 줄부터 뺀다.
     * 한패가 원문과 자리가 맞지 않으면 만들지 않는다 (엉뚱한 짝이 메모리를 망가뜨린다). 만들었으면 true.
     */
    private fun buildMemory(officialFile: File, patchFile: File, patchSha: String, log: (String) -> Unit): Boolean {
        if (repo.memoryPatchSha == patchSha && repo.memoryFile.isFile) return true
        val tmp = File(repo.memoryFile.path + ".tmp")
        val r = try {
            eng.buildMemory(officialFile, patchFile, repo.memoryFile, tmp, TranslationMemory.MAX_BYTES)
        } catch (t: Throwable) {
            tmp.delete() // 기존 메모리는 그대로 둔다
            throw t
        }
        if (!r.built) {
            tmp.delete()
            log("한패가 공식 원문과 자리가 맞지 않아 번역 메모리에 넣지 않았습니다 (${percent(r.alignment.ratio)} 일치)")
            return false
        }
        if (r.dropped > 0) log("번역 메모리가 최대 크기를 넘어 오래된 ${r.dropped}줄을 뺐습니다")
        if (!tmp.renameTo(repo.memoryFile)) {
            tmp.copyTo(repo.memoryFile, overwrite = true)
            tmp.delete()
        }
        repo.memorySize = r.size
        repo.memoryAt = System.currentTimeMillis()
        repo.memoryPatchSha = patchSha
        log("번역 메모리 갱신 · ${r.size}줄")
        return true
    }

    /**
     * 클라이언트마다 처음 한 번, 게임 폴더의 공식 원본을 백업 (원본 복원용).
     * 다른 도구로 넣은 한패 같은 원본이 아닌 파일은 백업하지 않는다 (복원하면 그 한패로 돌아간다).
     */
    private fun backupIfFirst(svc: IFileService, path: String, info: Inspection, t: PatchRepository.Target): String? {
        if (!info.exists || info.status != PatchStatus.OFFICIAL || t.backupFile.isFile) return null
        t.backupFile.parentFile?.mkdirs()
        return svc.copyFile(path, t.backupFile.absolutePath)
    }

    private fun applyLocked(svc: IFileService, pkg: String, log: (String) -> Unit, force: Boolean): ApplyOutcome {
        if (svc.isRunning(pkg)) return ApplyOutcome.GameRunning
        val src = repo.cacheFile
        val srcSha = repo.cachedSha
        if (!src.isFile || srcSha.isEmpty()) return ApplyOutcome.NoPatch
        val info = inspectLocked(svc, pkg, log) ?: return ApplyOutcome.Failed("게임 폴더를 읽지 못했습니다")
        val patchLayout = patchLayoutLocked()
        // 받아 둔 한패를 표로 읽지 못하면 버전을 확인할 수 없으므로 넣지 않는다
        if (patchLayout.isEmpty()) return ApplyOutcome.Failed("받아 둔 한패를 읽을 수 없습니다. 업데이트 확인으로 다시 받으세요")
        if (info.gameLayout.isNotEmpty() && info.gameLayout != patchLayout) {
            return ApplyOutcome.VersionMismatch
        }
        // 버전은 맞아도 번역이 엉뚱한 자리에 들어간 한패는 넣지 않는다 (같은 버전 공식 원문이 있을 때만 알 수 있다)
        // 번역 수정판은 이 검사에 걸리지 않는다 (번역 안 된 줄의 자리만 본다). 그래도 걸리면 사용자가 force 로 넣을 수 있다
        if (info.patchAligned == false && !force) return ApplyOutcome.Misaligned(info.alignRatio)
        if (info.patchAligned == false) log("자리 검사에 걸린 한패를 사용자 요청으로 넣습니다")
        val t = repo.target(pkg)
        val path = Paths.targetFile(pkg)
        if (info.exists && info.sha == srcSha) {
            t.appliedSha = srcSha
            t.repairedSha = ""
            return ApplyOutcome.Same
        }
        backupIfFirst(svc, path, info, t)?.let { return ApplyOutcome.Failed("백업 실패: $it") }
        svc.copyFile(src.absolutePath, path)?.let { return ApplyOutcome.Failed(it) }
        if (svc.sha256(path) != srcSha) return ApplyOutcome.Failed("복사 후 해시가 다릅니다")
        t.appliedSha = srcSha
        t.appliedAt = System.currentTimeMillis()
        t.repairedSha = ""
        // 이 버전 공식 원본을 보관해 뒀으면 번역 메모리를 최신 번역으로 갱신
        if (t.officialFile.isFile && t.officialLayout == patchLayout) {
            runCatching { buildMemory(t.officialFile, src, srcSha, log) }
                .onFailure { log("번역 메모리 갱신 실패: " + it.message) }
        }
        return ApplyOutcome.Applied
    }

    private fun repairLocked(svc: IFileService, pkg: String, log: (String) -> Unit): RepairOutcome {
        if (svc.isRunning(pkg)) return RepairOutcome.GameRunning
        val info = inspectLocked(svc, pkg, log) ?: return RepairOutcome.Failed("게임 폴더를 읽지 못했습니다")
        val t = repo.target(pkg)
        if (info.gameLayout.isEmpty() || !t.officialFile.isFile || t.officialLayout != info.gameLayout) {
            return RepairOutcome.NoOfficial
        }
        if (!repo.memoryFile.isFile) return RepairOutcome.NoMemory

        val out = t.repairedFile
        out.parentFile?.mkdirs()
        val result = try {
            eng.repair(t.officialFile, repo.memoryFile, oldPatchLocked(info.gameLayout), out)
        } catch (e: OutOfMemoryError) {
            // 표 세 개(새 원본·메모리·옛 한패)를 한꺼번에 못 올리는 폰이면 메모리만으로 복구한다
            log("메모리가 부족해 옛 한패 번역 옮기기를 건너뜁니다")
            eng.repair(t.officialFile, repo.memoryFile, null, out)
        }
        if (result.fromPatch > 0) log("업데이트 전 한패에서 번역 ${result.fromPatch}줄을 새 자리로 옮김")
        val sha = result.sha256 // 쓰면서 구한 해시 (다시 읽지 않는다)

        val path = Paths.targetFile(pkg)
        backupIfFirst(svc, path, info, t)?.let { return RepairOutcome.Failed("백업 실패: $it") }
        svc.copyFile(out.absolutePath, path)?.let { return RepairOutcome.Failed(it) }
        if (svc.sha256(path) != sha) return RepairOutcome.Failed("복사 후 해시가 다릅니다")
        t.repairedSha = sha
        t.repairedAt = System.currentTimeMillis()
        t.repairedCoverage = result.coverage.toFloat()
        repo.putLayout(sha, t.officialLayout)
        return RepairOutcome.Done(result.coverage, result.translated, result.leftChinese, result.fromPatch)
    }

    /**
     * 임시 복구 2단계에 쓸 옛 한패: 받아 둔 한패가 게임과 다른 버전(업데이트 전 한패)이고 번역 메모리에 아직 들어가지
     * 않은 것일 때만. 메모리가 이미 이 한패로 만들어졌으면 옮길 게 없고, 바뀐 원문에 옛 번역을 얹게 된다.
     */
    private fun oldPatchLocked(gameLayout: String): File? {
        val sha = repo.cachedSha
        if (sha.isEmpty() || !repo.cacheFile.isFile || sha == repo.memoryPatchSha) return null
        if (patchLayoutLocked() == gameLayout) return null
        return repo.cacheFile
    }

    /** 저장 공간 (바이트) */
    data class Storage(
        val cache: Long,     // 받은 한패 + 임시 복구본: 다시 받거나 만들 수 있다
        val memory: Long,    // 번역 메모리
        val official: Long,  // 공식 원본 보관본
        val backup: Long,    // 원본 백업
    ) {
        val total: Long get() = cache + memory + official + backup
    }

    fun storage(): Storage {
        val targets = Paths.GAME_PACKAGES.map { repo.target(it.pkg) }
        fun size(f: File) = if (f.isFile) f.length() else 0L
        return Storage(
            cache = size(repo.cacheFile) + targets.sumOf { size(it.repairedFile) } + dirSize(cacheDir),
            memory = size(repo.memoryFile),
            official = targets.sumOf { size(it.officialFile) },
            backup = targets.sumOf { size(it.backupFile) },
        )
    }

    /** 받은 한패·임시 복구본을 지운다. 기록(해시·버전 지문)은 남겨 상태 판정은 그대로다. */
    suspend fun clearCache(): Long = LOCK.withLock {
        withContext(Dispatchers.IO) {
            val files = listOf(repo.cacheFile) + Paths.GAME_PACKAGES.map { repo.target(it.pkg).repairedFile }
            files.sumOf { deleteFile(it) } + (cacheDir.listFiles()?.sumOf { f -> dirSize(f).also { f.deleteRecursively() } } ?: 0L)
        }
    }

    /** 번역 메모리를 지운다. 다음에 공식 원본과 같은 버전 한패가 모이면 다시 만든다. */
    suspend fun deleteMemory(): Long = LOCK.withLock {
        withContext(Dispatchers.IO) {
            deleteFile(repo.memoryFile).also {
                repo.memorySize = 0
                repo.memoryAt = 0
                repo.memoryPatchSha = ""
            }
        }
    }

    /** 공식 원본 보관본을 지운다. 게임 파일이 원본이면 다음 확인 때 다시 보관한다. */
    suspend fun deleteOfficial(): Long = LOCK.withLock {
        withContext(Dispatchers.IO) {
            Paths.GAME_PACKAGES.sumOf { g ->
                val t = repo.target(g.pkg)
                deleteFile(t.officialFile).also { t.officialSha = ""; t.officialLayout = "" }
            }
        }
    }

    /** 원본 백업을 지운다 (원본 복원을 못 하게 된다) */
    suspend fun deleteBackups(): Long = LOCK.withLock {
        withContext(Dispatchers.IO) { Paths.GAME_PACKAGES.sumOf { deleteFile(repo.target(it.pkg).backupFile) } }
    }

    private fun deleteFile(f: File): Long = if (f.isFile) f.length().also { f.delete() } else 0L

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    /**
     * 처음 적용할 때 백업한 파일로 되돌린다. 백업은 그때 게임 버전의 파일이라,
     * 그 뒤 게임이 업데이트됐으면 되돌리지 않는다 (옛 버전 원본을 넣으면 문장이 엉뚱해진다).
     */
    private fun restoreLocked(svc: IFileService, pkg: String, log: (String) -> Unit): RestoreOutcome {
        val t = repo.target(pkg)
        if (!t.backupFile.isFile) return RestoreOutcome.NoBackup
        if (svc.isRunning(pkg)) return RestoreOutcome.GameRunning
        val info = inspectLocked(svc, pkg, log) ?: return RestoreOutcome.Failed("게임 폴더를 읽지 못했습니다")
        val backupLayout = runCatching { eng.layoutKey(t.backupFile) }.getOrDefault("")
        if (info.gameLayout.isNotEmpty() && backupLayout != info.gameLayout) return RestoreOutcome.OldVersion
        svc.copyFile(t.backupFile.absolutePath, Paths.targetFile(pkg))?.let { return RestoreOutcome.Failed(it) }
        t.appliedSha = ""
        t.repairedSha = ""
        return RestoreOutcome.Restored
    }

    /** 1.0 이 처음 적용할 때 백업한 파일 중 공식 원본(한글 없음)인 것 */
    private fun legacyOfficial(): File? = Paths.GAME_PACKAGES
        .map { repo.target(it.pkg).backupFile }
        .firstOrNull { it.isFile && LangTable.hangulCount(it, HANGUL_SAMPLE_BYTES) == 0 }

    /**
     * 번역 메모리가 아직 없을 때: 1.0 이 남긴 원본 백업(그때 버전의 공식 원문)과 버전이 같은 옛 한패를
     * GitHub 이력에서 찾아 번역 메모리를 만든다. 색인(1MB 안쪽)만 받아 버전을 비교하고, 맞는 것 하나만 전부 받는다.
     */
    private fun bootstrapLocked(log: (String) -> Unit): Boolean {
        if (repo.memoryFile.isFile) return true
        val official = legacyOfficial() ?: return false
        val key = LangTable.chunkKey(official)
        val layout = eng.layoutKey(official)

        // 받아 둔 한패가 마침 같은 버전이면 그걸로 끝 (자리가 맞지 않으면 이력에서 다른 것을 찾는다)
        if (repo.cacheFile.isFile && patchLayoutLocked() == layout && buildMemory(official, repo.cacheFile, repo.cachedSha, log)) {
            return true
        }
        log("원본 백업과 같은 버전의 옛 한패를 GitHub 이력에서 찾는 중")
        val shas = runCatching { History.commitShas() }.getOrElse { log("이력 조회 실패: " + it.message); return false }
        val tmp = File(cacheDir, "history-patch.bytes")
        // 최신 커밋부터: 같은 버전 중 자리가 맞는 가장 최근 한패 (업데이트 직후 올라온 것은 어긋나 있기도 했다)
        for (sha in shas) {
            val header = runCatching { History.header(sha) }.getOrNull() ?: continue
            if (LangTable.chunkKeyOf(header) != key) continue
            try {
                History.download(sha, tmp)
                if (eng.layoutKey(tmp) != layout) continue
                if (!buildMemory(official, tmp, "history:$sha", log)) continue
                log("옛 한패(${sha.take(7)})로 번역 메모리 준비 완료")
                return true
            } catch (t: Throwable) {
                log("옛 한패 받기 실패: " + t.message)
            } finally {
                tmp.delete()
            }
        }
        log("원본 백업과 맞는 옛 한패를 찾지 못했습니다")
        return false
    }

    /** 한패 저장소의 커밋 이력 (GitHub API, 인증 없이 시간당 60회) */
    private object History {
        private const val REPO = "nemasdf/haguel-baefo"

        fun commitShas(): List<String> {
            val body = get("https://api.github.com/repos/$REPO/commits?path=${Paths.PATCH_FILE}&per_page=100", null)
            val arr = JSONArray(String(body))
            return (0 until arr.length()).map { arr.getJSONObject(it).getString("sha") }
        }

        private fun raw(sha: String) = "https://raw.githubusercontent.com/$REPO/$sha/${Paths.PATCH_FILE}"

        /** 파일 앞의 색인만 (길이 4바이트를 먼저 읽고 그만큼만) */
        fun header(sha: String): ByteArray? {
            val len4 = get(raw(sha), 0L to 3L)
            if (len4.size != 4) return null
            val len = (len4[0].toLong() and 0xff) or ((len4[1].toLong() and 0xff) shl 8) or
                ((len4[2].toLong() and 0xff) shl 16) or ((len4[3].toLong() and 0xff) shl 24)
            val header = get(raw(sha), 4L to 3L + len)
            return if (header.size.toLong() == len) header else null
        }

        fun download(sha: String, dst: File) {
            val conn = open(raw(sha), null)
            try {
                require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                conn.inputStream.use { input -> dst.outputStream().use { input.copyTo(it, 1 shl 16) } }
            } finally {
                conn.disconnect()
            }
        }

        private fun open(url: String, range: Pair<Long, Long>?): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SnqxKR")
                if (range != null) setRequestProperty("Range", "bytes=${range.first}-${range.second}")
            }

        private fun get(url: String, range: Pair<Long, Long>?): ByteArray {
            val conn = open(url, range)
            try {
                val ok = if (range != null) conn.responseCode == 206 else conn.responseCode == 200
                require(ok) { "HTTP ${conn.responseCode}" }
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        /** 화면과 백그라운드 작업이 게임 폴더·캐시·번역 메모리를 동시에 건드리지 않게 한다 */
        private val LOCK = Mutex()

        /** 보관하다 실패한 공식 원본의 해시 (LOCK 안에서만). 프로세스가 다시 뜨면 한 번 더 해 본다 */
        private val captureFailed = mutableSetOf<String>()

        /** 본문 앞 2MB 면 수천 줄이라 한패와 원본을 가르기에 충분하다 */
        const val HANGUL_SAMPLE_BYTES = 2 * 1024 * 1024
    }
}
