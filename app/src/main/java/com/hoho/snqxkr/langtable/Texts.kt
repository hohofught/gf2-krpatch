package com.hoho.snqxkr.langtable

import java.io.File
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 문장 목록. 문장을 힙에 복사해 들고 있지 않고 버퍼(보통 메모리 매핑한 파일) 안의 자리만 들고 있다.
 * 줄 i 는 bufs[src[i]] 의 off[i] 부터 len[i] 바이트다 (src 가 null 이면 모두 bufs[0]).
 *
 * 파일을 힙에 올리면 복구 한 번(새 원문 44MB·옛 한패 54MB·번역 메모리 32MB)에 힙이 190MB 넘게 들어
 * 힙이 256MB 인 폰에서 모자랐다. 매핑한 파일은 Java 힙 한도에 들지 않고, 메모리가 모자라면 OS 가 내렸다가
 * 다시 읽는다 (안드로이드가 resources.arsc 를 읽는 방식과 같다). 힙에는 줄마다 자리 9바이트만 남는다.
 *
 * 매핑한 파일은 읽는 동안 잘리거나 바뀌면 안 된다 (SIGBUS). 앱은 엔진이 읽는 파일을 모두 임시 파일에 쓴 뒤
 * 이름을 바꾸고, 엔진은 한 번에 하나씩 돈다 (PatchEngine 의 LOCK).
 */
class Texts internal constructor(
    internal val bufs: Array<ByteBuffer>,
    internal val src: ByteArray?,
    internal val off: IntArray,
    internal val len: IntArray,
) {
    init {
        require(off.size == len.size && (src == null || src.size == off.size))
        require(bufs.size <= Byte.MAX_VALUE) { "버퍼가 너무 많음" }
    }

    val size: Int get() = off.size

    fun length(i: Int): Int = len[i]

    internal fun bufOf(i: Int): Int = src?.get(i)?.toInt() ?: 0

    /** 줄 i 를 복사해 돌려준다 (시험·작은 곳용. 엔진은 [Cursor] 로 읽는다) */
    operator fun get(i: Int): ByteArray = cursor().let { c -> val n = c.load(i); c.bytes.copyOf(n) }

    fun cursor() = Cursor(this)

    /** 고른 줄만 (버퍼는 같이 쓴다) */
    internal fun select(rows: IntArray, n: Int = rows.size): Texts =
        Texts(bufs, src?.let { s -> ByteArray(n) { s[rows[it]] } }, IntArray(n) { off[rows[it]] }, IntArray(n) { len[rows[it]] })

    /**
     * 줄을 하나씩 읽는 곳. 줄 내용을 다시 쓰는 배열([bytes])에 복사해 준다 (한 줄씩이라 힙이 거의 늘지 않는다).
     * 버퍼의 위치를 옮기므로 스레드마다 따로 만든다.
     */
    class Cursor internal constructor(private val t: Texts) {
        private val views = Array(t.bufs.size) { t.bufs[it].duplicate() }

        var bytes = ByteArray(1024)
            private set

        /** 줄 i 를 [bytes] 앞에 복사하고 길이를 돌려준다 */
        fun load(i: Int): Int {
            val n = t.len[i]
            if (bytes.size < n) bytes = ByteArray(maxOf(n, bytes.size * 2))
            val v = views[t.bufOf(i)]
            // ByteBuffer.position(int) 은 자바 9 부터 ByteBuffer 를 돌려줘 옛 안드로이드에 없는 메서드가 된다. Buffer 로 부른다.
            (v as Buffer).position(t.off[i])
            v.get(bytes, 0, n)
            return n
        }

        /** 줄 i 가 다른 목록의 줄 j 와 바이트 단위로 같은지 (other 는 그 목록의 Cursor). 둘 다 읽어 둔다 */
        fun same(i: Int, other: Cursor, j: Int): Boolean {
            if (t.len[i] != other.t.len[j]) return false
            val la = load(i)
            val lb = other.load(j)
            return equal(bytes, la, other.bytes, lb)
        }
    }

    /**
     * 여러 목록에서 줄을 골라 만드는 새 목록 (임시 복구 결과: 원문·번역 메모리·옛 한패의 줄이 섞인다).
     * parts 의 버퍼를 이어 붙여 같이 쓰고, 줄마다 (버퍼 번호, 시작, 길이)만 적는다.
     */
    internal class Builder(parts: List<Texts>, n: Int) {
        private val base = IntArray(parts.size)
        private val view: Texts

        init {
            val all = ArrayList<ByteBuffer>()
            parts.forEachIndexed { k, p -> base[k] = all.size; all.addAll(p.bufs) }
            view = Texts(all.toTypedArray(), ByteArray(n), IntArray(n), IntArray(n))
        }

        /** 결과 줄 i = parts[part] 의 줄 j */
        fun set(i: Int, part: Int, from: Texts, j: Int) {
            view.src!![i] = (base[part] + from.bufOf(j)).toByte()
            view.off[i] = from.off[j]
            view.len[i] = from.len[j]
        }

        /** 결과 줄 i 가 parts[part] 에서 왔는지 */
        fun isFrom(i: Int, part: Int, from: Texts): Boolean {
            val b = view.src!![i] - base[part]
            return b >= 0 && b < from.bufs.size
        }

        /** 지금까지 채운 결과를 읽는 곳 */
        fun cursor() = view.cursor()

        /** 앞 count 줄로 끝낸다 (이후 set 하지 않는다) */
        fun build(count: Int = view.size): Texts =
            if (count == view.size) view
            else Texts(view.bufs, view.src!!.copyOf(count), view.off.copyOf(count), view.len.copyOf(count))
    }

    companion object {
        /** 바이트 배열들로 (시험·작은 표용). 힙 버퍼 하나에 이어 붙인다. */
        fun of(arrays: Array<ByteArray>): Texts {
            val total = arrays.sumOf { it.size.toLong() }
            require(total <= Int.MAX_VALUE) { "문장이 너무 큼" }
            val all = ByteArray(total.toInt())
            val off = IntArray(arrays.size)
            val len = IntArray(arrays.size)
            var p = 0
            for (i in arrays.indices) {
                System.arraycopy(arrays[i], 0, all, p, arrays[i].size)
                off[i] = p; len[i] = arrays[i].size; p += arrays[i].size
            }
            return Texts(arrayOf(ByteBuffer.wrap(all)), null, off, len)
        }

        /** a 의 앞 la 바이트와 b 의 앞 lb 바이트가 같은지 */
        fun equal(a: ByteArray, la: Int, b: ByteArray, lb: Int): Boolean {
            if (la != lb) return false
            for (k in 0 until la) if (a[k] != b[k]) return false
            return true
        }

        /** 파일을 읽기 전용으로 매핑한다. 2GB 넘는 파일은 다루지 않는다 (언어 표는 50MB 안팎). */
        internal fun map(file: File): ByteBuffer = RandomAccessFile(file, "r").use { f ->
            val size = f.length()
            require(size <= Int.MAX_VALUE) { "파일이 너무 큼" }
            f.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
        }
    }
}

/** 버퍼 위의 protobuf 읽기 (절대 위치로 읽어 버퍼 상태를 바꾸지 않는다). 규칙은 [Reader] 와 같다. */
internal class BufReader(private val b: ByteBuffer, private val end: Int) {
    var p = 0

    fun byte(): Int {
        if (p >= end) throw java.io.EOFException("파일이 중간에 끊김")
        return b.get(p++).toInt() and 0xff
    }

    fun varint(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val x = byte()
            r = r or ((x and 0x7f).toLong() shl s)
            if (x < 0x80) return r
            s += 7
            if (s > 63) throw java.io.IOException("varint 가 너무 김")
        }
    }

    fun u32le(): Long = byte().toLong() or (byte().toLong() shl 8) or (byte().toLong() shl 16) or (byte().toLong() shl 24)

    fun u32be(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()

    fun u64be(): Long = (u32be().toLong() shl 32) or (u32be().toLong() and 0xffffffffL)
}
