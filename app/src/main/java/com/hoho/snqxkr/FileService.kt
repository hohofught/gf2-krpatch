package com.hoho.snqxkr

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
        return try {
            val s = File(src)
            if (!s.isFile) return "원본 파일 없음: " + src
            val d = File(dst)
            d.parentFile?.let { if (!it.exists() && !it.mkdirs()) return "대상 폴더 생성 실패: " + it.path }
            val tmp = File(d.parentFile, d.name + ".tmp")
            s.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            if (tmp.length() != s.length()) {
                tmp.delete()
                return "복사 크기 불일치 (" + tmp.length() + " != " + s.length() + ")"
            }
            // FUSE(/sdcard) 에서는 rename 이 막히는 경우가 있어 실패하면 직접 덮어쓰기로 폴백
            if (d.exists()) d.delete()
            if (!tmp.renameTo(d)) {
                tmp.inputStream().use { i -> d.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                tmp.delete()
            }
            runCatching {
                d.setReadable(true, false)
                d.setWritable(true, false)
            }
            null
        } catch (t: Throwable) {
            t.toString()
        }
    }

    override fun deleteFile(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        return if (f.delete()) null else "삭제 실패: " + path
    }

    override fun listDir(path: String): Array<String> = File(path).list() ?: emptyArray()

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
