package com.hoho.snqxkr.langtable

import com.hoho.snqxkr.BuildConfig
import java.io.File

/** 번역 메모리 만들기 결과. built=false 면 한패가 공식 원문과 자리가 맞지 않아 만들지 않았다 (메모리를 더럽히지 않음) */
data class MemoryBuild(val built: Boolean, val size: Int, val dropped: Int, val alignment: PatchRepair.Alignment)

/** 임시 복구 결과. sha256 은 쓰면서 구한 복구본 해시 (다시 읽지 않는다) */
data class RepairOutput(val translated: Int, val leftChinese: Int, val fromPatch: Int, val sha256: String) {
    val coverage: Double get() = translated.toDouble() / (translated + leftChinese).coerceAtLeast(1)
}

/**
 * 파일 단위 번역 엔진. Kotlin 엔진과 C++ 네이티브 엔진(native 판에만 있음)이 같은 결과를 낸다
 * (LangTableTest, native/cli.cpp 로 확인).
 */
interface Engine {
    /** 화면·로그에 보이는 이름 */
    val name: String
    /** 게임 버전 지문 (Id 집합) */
    fun layoutKey(file: File): String
    fun alignment(official: File, patch: File): PatchRepair.Alignment
    /** 같은 버전 공식 원문 + 한패로 번역 메모리를 만들어 memoryIn 과 합치고 잘라 memoryOut 에 쓴다. 자리가 안 맞으면 만들지 않는다 */
    fun buildMemory(official: File, patch: File, memoryIn: File?, memoryOut: File, maxBytes: Long): MemoryBuild
    /** 새 공식 원문을 번역 메모리로 한국어화하고 oldPatch 의 번역을 새 자리로 옮겨 out 에 쓴다 */
    fun repair(official: File, memory: File, oldPatch: File?, out: File): RepairOutput
}

/** Kotlin 엔진 */
object KotlinEngine : Engine {
    override val name = "Kotlin"

    override fun layoutKey(file: File): String = LangTable.layoutKeyOf(LangTable.readIds(file))

    override fun alignment(official: File, patch: File) = PatchRepair.alignment(LangTable.read(official), LangTable.read(patch))

    override fun buildMemory(official: File, patch: File, memoryIn: File?, memoryOut: File, maxBytes: Long): MemoryBuild {
        val o = LangTable.read(official)
        val p = LangTable.read(patch)
        val a = PatchRepair.alignment(o, p)
        if (a.ok == false) return MemoryBuild(false, 0, 0, a)
        val fresh = TranslationMemory.build(o, p)
        val old = if (memoryIn != null && memoryIn.isFile) runCatching { TranslationMemory.read(memoryIn) }.getOrNull() else null
        val full = old?.mergedWith(fresh) ?: fresh
        val cut = full.capped(maxBytes)
        writing(memoryOut, official, patch, memoryIn) { cut.writeTo(it) }
        return MemoryBuild(true, cut.size, full.size - cut.size, a)
    }

    override fun repair(official: File, memory: File, oldPatch: File?, out: File): RepairOutput {
        val r = PatchRepair.repair(LangTable.read(official), TranslationMemory.read(memory), oldPatch?.let { LangTable.read(it) })
        val sha = writing(out, official, memory, oldPatch) { r.table.writeTo(it) }
        return RepairOutput(r.translated, r.leftChinese, r.fromPatch, sha)
    }

    /**
     * 입력은 매핑해 읽으므로, 출력이 입력과 같은 파일이면 쓰는 도중에 입력이 잘려 SIGBUS 로 죽는다.
     * 그럴 때만 옆 임시 파일에 다 쓴 뒤 이름을 바꾼다 (앱은 늘 다른 파일로 쓰지만 막아 둔다).
     */
    private inline fun <T> writing(out: File, vararg inputs: File?, write: (File) -> T): T {
        val target = out.canonicalFile
        if (inputs.none { it != null && it.canonicalFile == target }) return write(out)
        val tmp = File(out.path + ".tmp")
        val r = write(tmp)
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw java.io.IOException("출력 파일을 바꾸지 못함: $out")
        }
        return r
    }
}

/**
 * C++ 엔진 (native 판의 libsnqx.so, armv8-a).
 * 실패 이유는 [SnqxJni.lastError]. 메모리 부족이면 OutOfMemoryError 로 바꿔 던져 호출한 쪽이 줄여서 다시 하게 한다.
 */
object NativeEngine : Engine {
    override var name = "네이티브"
        private set

    fun load() {
        System.loadLibrary("snqx")
        name = "네이티브 (" + SnqxJni.version() + ")"
    }

    private fun fail(): Nothing {
        val msg = SnqxJni.lastError()
        if (msg == "메모리 부족") throw OutOfMemoryError(msg)
        throw java.io.IOException("네이티브 엔진: $msg")
    }

    override fun layoutKey(file: File): String = SnqxJni.layoutKey(file.path) ?: fail()

    override fun alignment(official: File, patch: File): PatchRepair.Alignment {
        val r = SnqxJni.alignment(official.path, patch.path) ?: fail()
        return PatchRepair.Alignment(r[0], r[1])
    }

    override fun buildMemory(official: File, patch: File, memoryIn: File?, memoryOut: File, maxBytes: Long): MemoryBuild {
        val r = SnqxJni.buildMemory(
            official.path, patch.path, memoryIn?.takeIf { it.isFile }?.path, memoryOut.path, maxBytes, PatchRepair.MIN_ALIGNMENT,
        ) ?: fail()
        return MemoryBuild(r[0] == 1L, r[1].toInt(), r[2].toInt(), PatchRepair.Alignment(r[3].toInt(), r[4].toInt()))
    }

    override fun repair(official: File, memory: File, oldPatch: File?, out: File): RepairOutput {
        val s = SnqxJni.repair(official.path, memory.path, oldPatch?.path, out.path) ?: fail()
        val (translated, left, moved, sha) = s.split(' ')
        return RepairOutput(translated.toInt(), left.toInt(), moved.toInt(), sha)
    }
}

/** native/jni.cpp 와 이름이 묶여 있다 */
internal object SnqxJni {
    external fun version(): String
    external fun lastError(): String
    external fun layoutKey(path: String): String?
    external fun alignment(official: String, patch: String): IntArray?
    external fun buildMemory(
        official: String, patch: String, memoryIn: String?, memoryOut: String, maxBytes: Long, minAlignment: Double,
    ): LongArray?
    external fun repair(official: String, memory: String, oldPatch: String?, out: String): String?
}

/** 지금 쓰는 엔진. native 판은 C++ 엔진을 올려 보고, 안 되면 Kotlin 엔진을 쓴다. */
object Engines {
    /** 네이티브 엔진을 못 올린 이유 (kotlin 판이거나 성공하면 null) */
    @Volatile
    var fallbackReason: String? = null
        private set

    val current: Engine by lazy {
        if (!BuildConfig.NATIVE_ENGINE) return@lazy KotlinEngine
        try {
            NativeEngine.load()
            NativeEngine
        } catch (t: Throwable) {
            fallbackReason = t.message ?: t.toString()
            KotlinEngine
        }
    }
}
