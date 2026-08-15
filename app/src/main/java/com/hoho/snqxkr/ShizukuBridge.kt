package com.hoho.snqxkr

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class ShizukuState {
    NOT_INSTALLED,   // Shizuku 앱 자체가 없음
    NOT_RUNNING,     // 설치는 됐지만 서비스 미실행
    NO_PERMISSION,   // 실행 중이지만 권한 미승인
    READY            // 사용 가능
}

/**
 * Shizuku 연결 + 권한 + UserService(FileService) 바인딩을 한 곳에서 관리한다.
 */
object ShizukuBridge {

    private const val REQUEST_CODE = 4242
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    @Volatile
    var service: IFileService? = null
        private set

    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val cb = pendingPermission
                pendingPermission = null
                cb?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }

    private var listenersRegistered = false

    fun init() {
        if (!listenersRegistered) {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            listenersRegistered = true
        }
    }

    fun state(context: Context): ShizukuState {
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        }.isSuccess
        if (!Shizuku.pingBinder()) {
            return if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.NO_PERMISSION
    }

    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        if (!Shizuku.pingBinder()) {
            cont.resume(false); return@suspendCancellableCoroutine
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            cont.resume(true); return@suspendCancellableCoroutine
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            cont.resume(false); return@suspendCancellableCoroutine
        }
        pendingPermission = { granted -> if (cont.isActive) cont.resume(granted) }
        Shizuku.requestPermission(REQUEST_CODE)
        cont.invokeOnCancellation { pendingPermission = null }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, FileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("filesvc")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private var connection: ServiceConnection? = null

    /** FileService 바인딩. 이미 연결돼 있으면 그대로 반환. */
    suspend fun bind(): Result<IFileService> {
        service?.let { if (it.asBinder().pingBinder()) return Result.success(it) }
        if (!Shizuku.pingBinder()) return Result.failure(IllegalStateException("Shizuku 서비스가 실행 중이 아닙니다"))
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(IllegalStateException("Shizuku 권한이 없습니다"))
        }
        return suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val svc = if (binder != null && binder.pingBinder()) {
                        IFileService.Stub.asInterface(binder)
                    } else null
                    service = svc
                    if (cont.isActive) {
                        if (svc != null) cont.resume(Result.success(svc))
                        else cont.resume(Result.failure(IllegalStateException("FileService 바인딩 실패")))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            connection = conn
            try {
                Shizuku.bindUserService(userServiceArgs, conn)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(Result.failure(t))
            }
        }
    }

    fun unbind() {
        val conn = connection ?: return
        runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
        connection = null
        service = null
    }
}
