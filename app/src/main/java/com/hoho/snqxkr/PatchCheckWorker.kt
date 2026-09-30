package com.hoho.snqxkr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 백그라운드 확인 (기본 꺼짐, 설정에서 켠다). 알림만 띄우고 게임 파일은 바꾸지 않는다
 * (적용·복구는 사용자가 알림을 탭해서 앱에서 한다).
 *
 * 배터리·백그라운드 점유를 줄이는 규칙:
 *  - WorkManager 주기 작업만 쓴다 (상주 서비스·wakelock·정확한 알람 없음, Doze·앱 대기 버킷을 따른다)
 *  - 네트워크 연결 + 배터리·저장공간 부족 아님 조건, flex 로 시스템이 다른 작업과 묶어 실행
 *  - 바뀐 게 없으면 한패는 HEAD(0바이트), 게임 파일은 크기·수정시각만 본다 (해시·본문 읽기 생략)
 *  - 볼 클라이언트가 없거나 알림이 전부 꺼져 있으면 Shizuku 를 깨우지 않는다
 *  - 끝나면 Shizuku 특권 프로세스를 내린다 (화면이 떠 있거나 화면의 작업이 도는 중이면 그 뒤로 미룬다)
 *  - 옛 한패를 받는 번역 메모리 준비 같은 무거운 일은 하지 않는다 (앱에서 버튼으로만)
 */
class PatchCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = PatchEngine(applicationContext)
        val repo = engine.repo
        if (!repo.autoCheck) return Result.success()

        // 1) 한패: 모바일 데이터에서는 설정이 켜져 있을 때만 받는다. 아니면 HEAD 로 변경 여부만.
        if (!isMetered(applicationContext) || repo.allowMobileData) {
            engine.sync()
        } else {
            engine.checkRemote() // 앱을 열 때와 같은 규칙 (실패하면 전에 본 값을 둔다)
        }

        // 2) 게임 폴더: 이 앱으로 한패를 쓴 적 있는 클라이언트만, 알림이 하나라도 켜져 있을 때만
        if (!Notices.anyEnabled(repo)) return done(repo, "한패만 확인 (알림 꺼짐)")
        val pm = applicationContext.packageManager
        val watched = Paths.GAME_PACKAGES.filter { game ->
            runCatching { pm.getPackageInfo(game.pkg, 0) }.isSuccess && Notices.used(repo, game.pkg)
        }
        if (watched.isEmpty()) return done(repo, "한패만 확인")
        val patchLayout = engine.patchLayout()

        // 무선 디버깅(shell) 방식은 재부팅하면 Shizuku 가 꺼진다. 그때는 마지막으로 본 게임 상태로만 판단한다.
        val shizukuUp = ShizukuBridge.awaitBinder(5_000) && ShizukuBridge.state(applicationContext) == ShizukuState.READY
        if (!shizukuUp) {
            for (game in watched) {
                val info = engine.lastKnown(game.pkg) ?: continue
                Notices.decide(engine, game, info, patchLayout)?.let { Notices.post(applicationContext, repo, game, it) }
            }
            return done(repo, "한패만 확인 · Shizuku 가 꺼져 있어 게임 폴더는 못 봄")
        }
        val note = try {
            ShizukuBridge.holding {
                val svc = ShizukuBridge.bind().getOrNull() ?: return@holding "한패만 확인 · Shizuku 연결 실패"
                for (game in watched) {
                    val info = engine.inspect(svc, game.pkg) ?: continue
                    Notices.decide(engine, game, info, patchLayout)?.let { Notices.post(applicationContext, repo, game, it) }
                }
                "한패·게임 폴더 확인"
            }
        } finally {
            // 화면이 적용·복구 중이면 그 작업이 끝난 뒤에 내린다
            ShizukuBridge.unbindWhenIdle()
        }
        return done(repo, note)
    }

    private fun done(repo: PatchRepository, note: String): Result {
        repo.lastCheckAt = System.currentTimeMillis()
        repo.lastCheckNote = note
        return Result.success()
    }

    companion object {
        private const val WORK = "patch-check"

        /**
         * 설정에 맞춰 주기 작업을 켜고 끈다. 꺼져 있으면 작업 자체를 지워 폰을 깨우지 않는다.
         * 주기를 바꾸면 UPDATE 로 기존 작업을 갈아 끼운다.
         */
        fun schedule(context: Context, enabled: Boolean, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val hours = intervalHours.coerceIn(6, 24).toLong()
            val request = PeriodicWorkRequestBuilder<PatchCheckWorker>(hours, TimeUnit.HOURS, hours / 4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun isMetered(context: Context): Boolean =
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    }
}

/** 알림 종류와 문구. 같은 사건(event)은 한 번만 알린다. 종류마다 채널을 나눠 시스템 설정에서도 따로 끌 수 있다. */
object Notices {
    const val CHANNEL_UNPATCHED = "patch-unpatched"
    const val CHANNEL_OFFICIAL = "patch-official"
    const val CHANNEL_UPDATE = "patch-update"
    const val EXTRA_PKG = "pkg"

    data class Notice(val event: String, val channel: String, val title: String, val text: String)

    fun anyEnabled(repo: PatchRepository) = repo.notifyUnpatched || repo.notifyOfficial || repo.notifyUpdate

    /** 이 앱으로 한패를 쓴 적이 있는 클라이언트만 챙긴다 */
    fun used(repo: PatchRepository, pkg: String): Boolean {
        val t = repo.target(pkg)
        return t.appliedSha.isNotEmpty() || t.repairedSha.isNotEmpty() || t.backupFile.isFile
    }

    fun decide(engine: PatchEngine, game: GamePkg, info: Inspection, patchLayout: String): Notice? {
        val repo = engine.repo
        if (!used(repo, game.pkg)) return null
        // 버전이 같아도 문장 자리가 원문과 어긋난 한패는 "정식 한패"로 알리지 않는다
        val matches = info.gameLayout.isNotEmpty() && info.gameLayout == patchLayout && info.patchAligned != false
        return when {
            info.status == PatchStatus.OFFICIAL && !matches && repo.notifyUnpatched -> Notice(
                "broken:" + info.gameLayout, CHANNEL_UNPATCHED,
                "${game.short} · 게임 업데이트로 한글패치가 풀렸습니다",
                if (engine.canRepair(game.pkg, info)) "탭해서 임시 복구하세요. 정식 한패가 나오면 다시 알려드립니다"
                else "새 한패를 기다리는 중입니다. 나오면 알려드립니다",
            )
            info.status == PatchStatus.OFFICIAL && matches && repo.notifyUnpatched -> Notice(
                "unpatched:" + info.sha, CHANNEL_UNPATCHED,
                "${game.short} · 한글패치가 풀렸습니다", "탭해서 다시 적용하세요",
            )
            info.status == PatchStatus.REPAIRED && matches && repo.notifyOfficial -> Notice(
                "official:" + repo.cachedSha, CHANNEL_OFFICIAL,
                "${game.short} · 정식 한글패치가 나왔습니다", "탭해서 임시 복구본을 정식 한패로 바꾸세요",
            )
            (info.status == PatchStatus.PATCHED_OLD || info.status == PatchStatus.FOREIGN) && matches && repo.notifyUpdate -> Notice(
                "update:" + repo.cachedSha, CHANNEL_UPDATE,
                "${game.short} · 한글패치가 갱신됐습니다", "탭해서 최신 한패로 바꾸세요",
            )
            else -> null
        }
    }

    fun post(context: Context, repo: PatchRepository, game: GamePkg, notice: Notice) {
        val t = repo.target(game.pkg)
        if (t.notifiedEvent == notice.event) return
        if (!permitted(context)) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, game.pkg.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PKG, game.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, notice.channel)
            .setSmallIcon(R.drawable.ic_stat_patch)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(game.pkg.hashCode(), n) }
        t.notifiedEvent = notice.event
    }

    fun permitted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // 1차에 만들었던 채널 정리
        nm.deleteNotificationChannel("patch-alert")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UNPATCHED, "한글패치가 풀렸을 때", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "게임 업데이트 등으로 게임 폴더의 한패가 원본으로 바뀌었을 때" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFICIAL, "정식 한글패치가 나왔을 때", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "임시 복구 중에 게임 버전에 맞는 한패가 올라왔을 때" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, "한패 번역 갱신", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "같은 게임 버전 한패의 번역이 고쳐졌을 때 (자주 올 수 있음)" }
        )
    }
}
