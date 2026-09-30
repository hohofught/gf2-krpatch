package com.hoho.snqxkr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class GamePkg(val pkg: String, val label: String, val short: String)

object Paths {
    const val PATCH_FILE = "LangPackageTableCnData.bytes"

    /** arca 글의 unsafelink 래퍼를 벗긴 원본 주소 */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/nemasdf/haguel-baefo/refs/heads/main/LangPackageTableCnData.bytes"

    /** 중섭 패키지만. 글로벌·한국 서버 클라이언트는 여기에 절대 넣지 않는다. */
    val GAME_PACKAGES = listOf(
        GamePkg("com.Sunborn.SnqxExilium", "중섭 공식(官服)", "官服"),
        GamePkg("com.Sunborn.SnqxExilium.bilibili", "중섭 빌리빌리(B服)", "B服"),
        GamePkg("com.Sunborn.SnqxExilium.qq", "중섭 QQ", "QQ"),
    )

    /**
     * 글로벌 서버 패키지(com.Sunborn.SnqxExilium.Glo)는 이름 앞부분이 중섭과 같다.
     * 어떤 경로로든 허용 목록 밖의 패키지 경로가 만들어지지 않도록 여기서 막는다.
     */
    fun tableDir(pkg: String): String {
        require(GAME_PACKAGES.any { it.pkg == pkg }) { "중섭 패키지가 아님: $pkg" }
        return "/storage/emulated/0/Android/data/$pkg/files/LocalCache/Data/Table"
    }

    fun targetFile(pkg: String) = tableDir(pkg) + "/" + PATCH_FILE
}

sealed interface SyncResult {
    /** 서버 파일이 캐시와 동일 → 다운로드 생략 */
    data class UpToDate(val sha256: String, val size: Long, val via: String) : SyncResult
    data class Downloaded(val sha256: String, val size: Long) : SyncResult
    data class Failed(val message: String) : SyncResult
}

class PatchRepository(private val app: Context) {

    private val prefs = app.getSharedPreferences("snqxkr", Context.MODE_PRIVATE)

    private val root: File get() = app.getExternalFilesDir(null)!!
    val cacheFile: File get() = File(root, "patch/" + Paths.PATCH_FILE)

    /** 클라이언트(패키지)마다 따로 두는 적용 기록과 원본 백업. 官服·B服 를 같이 깔아도 섞이지 않는다. */
    inner class Target(val pkg: String) {
        /** 처음 적용할 때 게임 폴더에 있던 파일 (원본 복원용, 한 번만 만든다) */
        val backupFile: File get() = File(root, "backup/$pkg/" + Paths.PATCH_FILE + ".orig")

        /** 지금 게임 버전의 공식 원본(중국어). 번역 메모리를 만들고 업데이트 후 복구할 때 쓴다. */
        val officialFile: File get() = File(root, "official/$pkg/" + Paths.PATCH_FILE)

        /** 번역 메모리로 만든 임시 복구본 */
        val repairedFile: File get() = File(root, "repaired/$pkg/" + Paths.PATCH_FILE)

        var appliedSha: String by stringPref("appliedSha:$pkg")
        var appliedAt: Long by longPref("appliedAt:$pkg")
        var officialSha: String by stringPref("officialSha:$pkg")
        var officialLayout: String by stringPref("officialLayout:$pkg")
        var repairedSha: String by stringPref("repairedSha:$pkg")
        var repairedAt: Long by longPref("repairedAt:$pkg")
        var repairedCoverage: Float by floatPref("repairedCoverage:$pkg", -1f)

        /** 마지막으로 알림을 띄운 사건. 같은 사건으로 두 번 알리지 않는다. */
        var notifiedEvent: String by stringPref("notified:$pkg")

        /**
         * 게임 폴더 파일을 마지막으로 읽었을 때의 "크기:수정시각|sha|한글수".
         * 크기와 수정시각이 그대로면 56MB 해시·본문 읽기를 건너뛴다 (배터리).
         */
        var seen: String by stringPref("seen:$pkg")

        /** 마지막으로 확인한 게임 폴더 파일의 상태. Shizuku 가 꺼져 있을 때(무선 디버깅은 재부팅하면 꺼진다) 대신 쓴다. */
        var lastStatus: String by stringPref("lastStatus:$pkg")
    }

    /** 마지막 백그라운드 확인 시각과 결과 한 줄 */
    var lastCheckAt: Long by longPref("lastCheckAt")
    var lastCheckNote: String by stringPref("lastCheckNote")

    /** 중국어 원문 → 한국어 번역 메모리 (여러 게임 버전을 합쳐 둔다) */
    val memoryFile: File get() = File(app.filesDir, "translation-memory.bin")
    var memorySize: Int by intPref("memorySize")
    var memoryAt: Long by longPref("memoryAt")

    /** 번역 메모리에 마지막으로 넣은 한패 (같은 한패로 다시 만들지 않는다) */
    var memoryPatchSha: String by stringPref("memoryPatchSha")

    /** 파일 SHA-256 → 게임 버전 지문. 50MB 를 다시 훑지 않도록 기억해 둔다. */
    fun layoutFor(sha: String): String? = prefs.getString("layout:$sha", null)
    fun putLayout(sha: String, layout: String) = prefs.edit().putString("layout:$sha", layout).apply()

    /** (한패, 공식 원문) 짝의 자리 검사 결과 "번역안된줄/같은자리" */
    fun alignmentFor(key: String): String? = prefs.getString("align:$key", null)
    fun putAlignment(key: String, value: String) = prefs.edit().putString("align:$key", value).apply()

    /**
     * 백그라운드 확인. 기본은 꺼짐: 켜지 않으면 예약 작업 자체가 없어서 폰을 깨우지 않는다.
     * (1.0 은 이 값을 쓰지 않았으므로 기존 값이 있어도 새 키로 시작한다)
     */
    var autoCheck: Boolean by boolPref("backgroundCheck", false)

    /** 백그라운드 확인 주기 (시간) */
    var checkIntervalHours: Int
        get() = prefs.getInt("checkIntervalHours", 12)
        set(v) = prefs.edit().putInt("checkIntervalHours", v).apply()

    /** 백그라운드에서 모바일 데이터로도 한패(약 56MB)를 받을지. 꺼져 있으면 Wi-Fi 에서만 받는다. */
    var allowMobileData: Boolean by boolPref("allowMobileData", false)

    /** 알림 종류별 켜기/끄기. 번역 갱신은 자주 올 수 있어 기본 꺼짐. */
    var notifyUnpatched: Boolean by boolPref("notifyUnpatched", true)
    var notifyOfficial: Boolean by boolPref("notifyOfficial", true)
    var notifyUpdate: Boolean by boolPref("notifyUpdate", false)

    /** 서버에 새 한패가 있는데 모바일 데이터라 아직 못 받음 */
    var remotePending: Boolean by boolPref("remotePending", false)

    private fun stringPref(key: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, "")!!
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = prefs.edit().putString(key, value).apply()
    }

    private fun longPref(key: String) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getLong(key, 0L)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = prefs.edit().putLong(key, value).apply()
    }

    private fun intPref(key: String) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, 0)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = prefs.edit().putInt(key, value).apply()
    }

    private fun floatPref(key: String, default: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getFloat(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) = prefs.edit().putFloat(key, value).apply()
    }

    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    fun target(pkg: String) = Target(pkg)

    /** 사용자가 고른 클라이언트 */
    var selectedPkg: String
        get() = prefs.getString("selectedPkg", "")!!
        set(v) = prefs.edit().putString("selectedPkg", v).apply()

    /**
     * 1.0 은 클라이언트 구분 없이 기록했다. 1.0 이 쓰던 클라이언트(목록 순서상 처음 설치된 것)로 옮긴다.
     */
    fun migrateLegacy(pkg: String) {
        val t = target(pkg)
        val legacyBackup = File(root, "backup/" + Paths.PATCH_FILE + ".orig")
        if (legacyBackup.isFile && !t.backupFile.isFile) {
            t.backupFile.parentFile?.mkdirs()
            if (!legacyBackup.renameTo(t.backupFile)) {
                legacyBackup.copyTo(t.backupFile, overwrite = true)
                legacyBackup.delete()
            }
        }
        if (prefs.contains("appliedSha")) {
            t.appliedSha = prefs.getString("appliedSha", "")!!
            t.appliedAt = prefs.getLong("appliedAt", 0L)
            prefs.edit().remove("appliedSha").remove("appliedAt").apply()
        }
    }

    var url: String
        get() = prefs.getString("url", Paths.DEFAULT_URL)!!
        set(v) = prefs.edit().putString("url", v).apply()

    /** 서버가 준 ETag (raw.githubusercontent 는 내용 해시를 그대로 준다) */
    var etag: String
        get() = prefs.getString("etag", "")!!
        set(v) = prefs.edit().putString("etag", v).apply()

    /** 캐시된 패치 파일의 SHA-256 */
    var cachedSha: String
        get() = prefs.getString("cachedSha", "")!!
        set(v) = prefs.edit().putString("cachedSha", v).apply()

    var checkedAt: Long
        get() = prefs.getLong("checkedAt", 0L)
        set(v) = prefs.edit().putLong("checkedAt", v).apply()

    /** 서버 파일의 현재 ETag 만 묻는다 (본문 0바이트). 실패하면 null. */
    fun remoteEtag(): String? = runCatching {
        val head = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            head.connect()
            head.getHeaderField("ETag")?.trim('"', 'W', '/')
        } finally {
            head.disconnect()
        }
    }.getOrNull()

    fun sha256(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * 서버 파일을 캐시와 맞춘다. 중복 다운로드는 3단으로 막는다.
     *  1) HEAD 로 ETag/크기 비교 → 같으면 바로 종료
     *  2) GET 에 If-None-Match → 304 면 본문 없이 종료
     *  3) 받은 뒤 SHA-256 이 캐시와 같으면 파일 교체 자체를 생략
     */
    suspend fun sync(onProgress: (downloaded: Long, total: Long) -> Unit): SyncResult =
        withContext(Dispatchers.IO) {
            val target = url
            val haveCache = cacheFile.isFile && cachedSha.isNotEmpty() &&
                    sha256(cacheFile) == cachedSha

            // 1) HEAD
            if (haveCache && etag.isNotEmpty()) {
                val head = runCatching {
                    (URL(target).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 15000
                        readTimeout = 15000
                        instanceFollowRedirects = true
                    }
                }.getOrNull()
                if (head != null) {
                    val remoteTag = runCatching {
                        head.connect()
                        head.getHeaderField("ETag")?.trim('"', 'W', '/')
                    }.getOrNull()
                    runCatching { head.disconnect() }
                    if (!remoteTag.isNullOrEmpty() && remoteTag == etag) {
                        checkedAt = System.currentTimeMillis()
                        return@withContext SyncResult.UpToDate(cachedSha, cacheFile.length(), "ETag 동일")
                    }
                }
            }

            // 2) GET (조건부)
            val conn = runCatching {
                (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    if (haveCache && etag.isNotEmpty()) setRequestProperty("If-None-Match", "\"" + etag + "\"")
                }
            }.getOrElse { return@withContext SyncResult.Failed("연결 실패: " + it.message) }

            val code = runCatching { conn.responseCode }
                .getOrElse { return@withContext SyncResult.Failed("응답 없음: " + it.message) }

            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                conn.disconnect()
                checkedAt = System.currentTimeMillis()
                return@withContext SyncResult.UpToDate(cachedSha, cacheFile.length(), "304 Not Modified")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext SyncResult.Failed("HTTP " + code)
            }

            val total = conn.contentLengthLong
            val newTag = conn.getHeaderField("ETag")?.trim('"', 'W', '/').orEmpty()
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".part")

            val digest = MessageDigest.getInstance("SHA-256")
            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                        onProgress(done, total)
                    }
                }
            } catch (t: Throwable) {
                tmp.delete()
                return@withContext SyncResult.Failed("다운로드 실패: " + t.message)
            } finally {
                conn.disconnect()
            }

            if (total > 0 && tmp.length() != total) {
                tmp.delete()
                return@withContext SyncResult.Failed("크기 불일치 (" + tmp.length() + " / " + total + ")")
            }

            val newSha = digest.digest().joinToString("") { "%02x".format(it) }

            // 3) 내용이 같으면 파일 교체 생략
            if (haveCache && newSha == cachedSha) {
                tmp.delete()
                etag = newTag.ifEmpty { etag }
                checkedAt = System.currentTimeMillis()
                return@withContext SyncResult.UpToDate(cachedSha, cacheFile.length(), "내용 해시 동일")
            }

            if (cacheFile.exists()) cacheFile.delete()
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
            cachedSha = newSha
            etag = newTag
            checkedAt = System.currentTimeMillis()
            SyncResult.Downloaded(newSha, cacheFile.length())
        }
}
