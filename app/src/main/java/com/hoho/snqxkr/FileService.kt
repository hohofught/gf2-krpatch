package com.hoho.snqxkr

import com.hoho.snqxkr.langtable.LangTable
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-spawned process (root or shell uid), which is the only way to touch
 * /sdcard/Android/data/<game>/... on Android 13+. Instantiated by Shizuku via reflection, so it
 * must keep a no-arg constructor and must not be obfuscated.
 */
class FileService : IFileService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDir(path: String): Boolean = File(path).isDirectory

    override fun size(path: String): Long = File(path).let { if (it.exists()) it.length() else -1L }

    override fun lastModified(path: String): Long = File(path).lastModified()

    override fun selfUid(): Int = android.os.Process.myUid()

    override fun sha256(path: String): String? {
        val f = File(path)
        if (!f.isFile) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            null
        }
    }

    override fun copyFile(src: String, dst: String): String? {
        val s = File(src)
        val d = File(dst)
        val tmp = File(d.parentFile, d.name + ".tmp")
        return try {
            if (!s.isFile) return "원본 파일 없음: " + src
            d.parentFile?.let { if (!it.exists() && !it.mkdirs()) return "대상 폴더 생성 실패: " + it.path }
            copy(s, tmp)
            if (tmp.length() != s.length()) {
                tmp.delete()
                return "복사 크기 불일치 (" + tmp.length() + " != " + s.length() + ")"
            }
            replace(tmp, d)
            runCatching {
                d.setReadable(true, false)
                d.setWritable(true, false)
            }
            null
        } catch (t: Throwable) {
            tmp.delete()
            t.toString()
        }
    }

    /**
     * tmp 로 dst 를 바꾼다. 원래 파일은 다 바꿀 때까지 옆(.old)에 두고, 도중에 실패하면 되돌린다
     * (게임 파일이 없어지거나 반쯤 쓰인 채로 남지 않게). 이 프로세스가 도중에 죽어 .old 만 남았으면 다음에 먼저 되살린다.
     * FUSE(/sdcard) 에서는 rename 이 막히는 경우가 있어 그때는 복사로 한다.
     */
    private fun replace(tmp: File, dst: File) {
        val old = File(dst.parentFile, dst.name + ".old")
        if (old.isFile) {
            if (dst.exists()) old.delete() else moveOrCopy(old, dst)
        }
        val had = dst.exists()
        if (had) moveOrCopy(dst, old, keepSource = true)
        try {
            moveOrCopy(tmp, dst)
        } catch (t: Throwable) {
            if (had) runCatching { moveOrCopy(old, dst) }
            throw t
        }
        if (had) old.delete()
    }

    /** from 을 to 로 옮긴다. rename 이 안 되면 복사하고 크기를 확인한다. keepSource 면 복사했을 때 from 을 지우지 않는다 */
    private fun moveOrCopy(from: File, to: File, keepSource: Boolean = false) {
        if (from.renameTo(to)) return
        copy(from, to)
        if (to.length() != from.length()) throw java.io.IOException("복사 크기 불일치 (" + to.length() + " != " + from.length() + ")")
        if (!keepSource) from.delete()
    }

    private fun copy(from: File, to: File) {
        from.inputStream().use { i ->
            java.io.FileOutputStream(to).use { o ->
                i.copyTo(o, 1 shl 16)
                runCatching { o.fd.sync() } // 이름을 바꾸기 전에 내용을 디스크에 (지원하지 않는 파일 시스템도 있다)
            }
        }
    }

    override fun deleteFile(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        return if (f.delete()) null else "삭제 실패: " + path
    }

    override fun listDir(path: String): Array<String> = File(path).list() ?: emptyArray()

    override fun hangulCount(path: String, maxBytes: Int): Int = LangTable.hangulCount(File(path), maxBytes)

    /** 게임 버전 지문. 앱은 게임 폴더를 직접 못 읽으므로 이 프로세스에서 Id 만 훑어 계산한다. */
    override fun tableLayout(path: String): String? =
        runCatching { LangTable.layoutKeyOf(LangTable.readIds(File(path))) }.getOrNull()

    override fun isRunning(pkg: String): Boolean {
        val proc = File("/proc")
        val pids = proc.list() ?: return false
        for (name in pids) {
            if (name.toIntOrNull() == null) continue
            val cmdline = File(proc, name + "/cmdline")
            val line = try {
                if (!cmdline.canRead()) continue
                val raw = cmdline.readBytes()
                // /proc/<pid>/cmdline 은 인자를 0 바이트로 구분한다
                val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
                String(raw, 0, end, Charsets.UTF_8).trim()
            } catch (t: Throwable) {
                continue
            }
            if (line == pkg || line.startsWith(pkg + ":")) return true
        }
        return false
    }
}
