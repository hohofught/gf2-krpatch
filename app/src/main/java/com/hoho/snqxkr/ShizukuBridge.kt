package com.hoho.snqxkr

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class ShizukuState {
    NOT_INSTALLED,   // Shizuku 앱도 Sui 도 없음
    NOT_RUNNING,     // 설치는 됐지만 서비스 미실행
    OUTDATED,        // v11 미만 Shizuku (현재 API 미지원)
    NO_PERMISSION,   // 실행 중, 아직 권한을 묻지 않았거나 한 번 거부
    DENIED,          // "다시 묻지 않음"으로 거부 → Shizuku 앱에서 직접 허용해야 함
    READY            // 사용 가능
}

/**
 * Shizuku 연결 + 권한 + UserService(FileService) 바인딩을 한 곳에서 관리한다.
 * 리스너 등록·해제와 권한 요청 순서는 Shizuku-API README 의 공식 예제를 따른다.
 */
object ShizukuBridge {

    private const val REQUEST_CODE = 4242
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

    /** FileService 가 이 시간 안에 안 붙으면 실패로 본다 (첫 실행은 dex 를 올리느라 몇 초 걸린다) */
    private const val BIND_TIMEOUT_MS = 15_000L

    @Volatile
    var service: IFileService? = null
        private set

    /** 화면이 보이는 동안은 백그라운드 작업이 끝나도 특권 프로세스를 내리지 않는다 */
    @Volatile
    var uiVisible = false

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 바인더 연결·끊김과 권한 결과마다 흘린다. 받는 쪽은 state() 를 다시 읽으면 된다. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { _changes.tryEmit(Unit) }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Shizuku 가 내려가면 UserService 도 함께 죽는다
        service = null
        connection = null
        _changes.tryEmit(Unit)
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
            val cb = pendingPermission
            pendingPermission = null
            cb?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
            _changes.tryEmit(Unit)
        }

    /** Activity.onCreate 에서 호출. Sticky 라 이미 받은 바인더도 곧바로 통지된다. */
    fun addListeners() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    /** Activity.onDestroy 에서 호출 */
    fun removeListeners() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun state(context: Context): ShizukuState {
        if (!Shizuku.pingBinder()) {
            val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PKG, 0) }.isSuccess
            return if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        // 바인더가 살아 있을 때만 Shizuku 메서드를 부를 수 있고, 그 사이 죽으면 IllegalStateException 이 난다
        return runCatching {
            when {
                Shizuku.isPreV11() -> ShizukuState.OUTDATED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
                else -> ShizukuState.NO_PERMISSION
            }
        }.getOrDefault(ShizukuState.NOT_RUNNING)
    }

    /**
     * 공식 checkPermission 흐름.
     *  허용됨 → true
     *  "다시 묻지 않음"으로 거부됨 → false (요청 창이 뜨지 않으므로 Shizuku 앱에서 허용하도록 안내)
     *  그 외 → requestPermission 후 결과를 기다린다
     */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val immediate = runCatching {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> false
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                Shizuku.shouldShowRequestPermissionRationale() -> false
                else -> null
            }
        }.getOrDefault(false)
        if (immediate != null) {
            cont.resume(immediate)
            return@suspendCancellableCoroutine
        }
        pendingPermission = { granted -> if (cont.isActive) cont.resume(granted) }
        cont.invokeOnCancellation { pendingPermission = null }
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * 백그라운드 작업처럼 앱 프로세스가 막 떴을 때는 바인더가 조금 늦게 온다. 올 때까지 잠깐 기다린다.
     * Shizuku 가 꺼져 있으면 timeoutMs 뒤 false.
     */
    suspend fun awaitBinder(timeoutMs: Long): Boolean {
        if (Shizuku.pingBinder()) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Shizuku.OnBinderReceivedListener {
                    override fun onBinderReceived() {
                        Shizuku.removeBinderReceivedListener(this)
                        if (cont.isActive) cont.resume(true)
                    }
                }
                Shizuku.addBinderReceivedListenerSticky(listener)
                cont.invokeOnCancellation { Shizuku.removeBinderReceivedListener(listener) }
            }
        } ?: false
    }

    /** Shizuku 앱 열기 (서비스 시작, 앱별 권한 관리). 없으면 false. */
    fun openShizuku(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        context.startActivity(intent)
        return true
    }

    /** 설치·업데이트 페이지. Play 스토어가 없으면 공식 다운로드 페이지. */
    fun openInstallPage(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("filesvc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    @Volatile
    private var connection: ServiceConnection? = null

    /** 화면과 백그라운드 작업이 동시에 붙어도 연결은 하나만 만든다 */
    private val bindLock = Mutex()

    /** FileService 바인딩. 이미 연결돼 있으면 그대로 반환. [BIND_TIMEOUT_MS] 안에 안 붙으면 실패. */
    suspend fun bind(): Result<IFileService> = bindLock.withLock {
        service?.let { if (it.asBinder().pingBinder()) return@withLock Result.success(it) }
        val ready = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ready) return@withLock Result.failure(IllegalStateException("Shizuku 가 준비되지 않았습니다"))
        // 죽은 서비스에 걸려 있던 연결은 풀고 새로 붙는다
        connection?.let { old -> runCatching { Shizuku.unbindUserService(userServiceArgs, old, false) } }
        connection = null
        withTimeoutOrNull(BIND_TIMEOUT_MS) { connect() }
            ?: Result.failure(IllegalStateException("FileService 연결 시간 초과 · Shizuku 앱에서 서비스가 실행 중인지 확인하세요"))
    }

    private suspend fun connect(): Result<IFileService> = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = if (binder != null && binder.pingBinder()) {
                    IFileService.Stub.asInterface(binder)
                } else null
                // 시간이 지나 포기한 연결이 늦게 붙은 경우는 쓰지 않는다
                if (connection === this) service = svc
                if (cont.isActive) {
                    if (svc != null) cont.resume(Result.success(svc))
                    else cont.resume(Result.failure(IllegalStateException("FileService 바인딩 실패")))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection === this) service = null
            }
        }
        connection = conn
        // 시간 초과·취소면 걸어 둔 연결을 거둔다 (반쯤 뜬 서비스도 내려 다음 연결이 새로 시작하게)
        cont.invokeOnCancellation {
            if (connection === conn) connection = null
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
        }
        try {
            Shizuku.bindUserService(userServiceArgs, conn)
        } catch (t: Throwable) {
            if (connection === conn) connection = null
            if (cont.isActive) cont.resume(Result.failure(t))
        }
    }

    private val holdLock = Any()
    private var holders = 0
    private var unbindPending = false

    /**
     * 게임 폴더 작업 동안 특권 프로세스를 붙잡아 둔다. 그동안 [unbindWhenIdle] 은 작업이 끝날 때로 미뤄진다
     * (복사 도중에 프로세스를 내리면 게임 파일이 반쯤 쓰인 채로 남을 수 있다).
     */
    suspend fun <T> holding(block: suspend () -> T): T {
        synchronized(holdLock) { holders++ }
        try {
            return block()
        } finally {
            val unbindNow = synchronized(holdLock) {
                holders--
                (holders == 0 && unbindPending).also { if (it) unbindPending = false }
            }
            if (unbindNow && !uiVisible) unbind()
        }
    }

    /**
     * 화면이 안 보일 때 특권 프로세스를 내린다 (백그라운드 작업이 끝날 때, 화면을 닫을 때).
     * 누가 [holding] 중이면 그 작업이 끝날 때 다시 판단한다.
     */
    fun unbindWhenIdle() {
        val busy = synchronized(holdLock) { (holders > 0).also { if (it) unbindPending = true } }
        if (!busy && !uiVisible) unbind()
    }

    private fun unbind() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
    }
}
