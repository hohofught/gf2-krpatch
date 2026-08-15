package com.hoho.snqxkr

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class GamePkg(val pkg: String, val label: String)

object Paths {
    const val PATCH_FILE = "LangPackageTableCnData.bytes"

    /** arca 글의 unsafelink 래퍼를 벗긴 원본 주소 */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/nemasdf/haguel-baefo/refs/heads/main/LangPackageTableCnData.bytes"

    val GAME_PACKAGES = listOf(
        GamePkg("com.Sunborn.SnqxExilium", "중섭 공식(官服)"),
        GamePkg("com.Sunborn.SnqxExilium.bilibili", "중섭 비리비리"),
        GamePkg("com.Sunborn.SnqxExilium.qq", "중섭 QQ"),
    )

    fun tableDir(pkg: String) =
        "/storage/emulated/0/Android/data/$pkg/files/LocalCache/Data/Table"

    fun targetFile(pkg: String) = tableDir(pkg) + "/" + PATCH_FILE
}

sealed interface SyncResult {
    /** 서버 파일이 캐시와 동일 → 다운로드 생략 */
    data class UpToDate(val sha256: String, val size: Long, val via: String) : SyncResult
    data class Downloaded(val sha256: String, val size: Long) : SyncResult
    data class Failed(val message: String) : SyncResult
}

class PatchRepository(private val app: Application) {

    private val prefs = app.getSharedPreferences("snqxkr", Context.MODE_PRIVATE)

    private val root: File get() = app.getExternalFilesDir(null)!!
    val cacheFile: File get() = File(root, "patch/" + Paths.PATCH_FILE)
    val backupFile: File get() = File(root, "backup/" + Paths.PATCH_FILE + ".orig")

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

    var appliedSha: String
        get() = prefs.getString("appliedSha", "")!!
        set(v) = prefs.edit().putString("appliedSha", v).apply()

    var appliedAt: Long
        get() = prefs.getLong("appliedAt", 0L)
        set(v) = prefs.edit().putLong("appliedAt", v).apply()

    var checkedAt: Long
        get() = prefs.getLong("checkedAt", 0L)
        set(v) = prefs.edit().putLong("checkedAt", v).apply()

    var autoCheck: Boolean
        get() = prefs.getBoolean("autoCheck", true)
        set(v) = prefs.edit().putBoolean("autoCheck", v).apply()

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
