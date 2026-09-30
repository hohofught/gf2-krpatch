package com.hoho.snqxkr.langtable

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * LangPackageTable*.bytes 한 파일. 형식은 docs/lang-table-format.md 참고.
 *
 *  [u32 LE 색인 길이][색인 protobuf][본문 protobuf]
 *  본문: repeated TextMap { int64 Id = 1; string Content = 2 }
 *  색인: 1 = 청크 크기(10), 2 = 1, 3 = repeated { 청크 번호(Id/10), { 본문 내 offset, size } }
 *
 * 문장은 UTF-8 바이트 그대로다 (String 으로 바꾸면 메모리가 두 배 든다). [read] 는 파일을 매핑하고
 * 문장마다 파일 안의 자리만 들고 있다 ([Texts]). 빈 문장은 길이 0 이다 (proto3 는 빈 문자열 필드를 생략한다).
 */
class LangTable(
    val ids: LongArray,
    val texts: Texts,
    val chunkSize: Long = 10,
    val headerFlag: Long = 1,
) {
    /** 바이트 배열들로 (시험·작은 표용) */
    constructor(ids: LongArray, texts: Array<ByteArray>, chunkSize: Long = 10, headerFlag: Long = 1) :
        this(ids, Texts.of(texts), chunkSize, headerFlag)

    init {
        require(ids.size == texts.size)
    }

    val size: Int get() = ids.size

    /** 게임 버전 구분용 지문 ([layoutKeyOf]) */
    fun layoutKey(): String = layoutKeyOf(ids)

    private val order: IntArray by lazy { sortedOrderOf(ids) }

    /** Id 오름차순으로 늘어선 위치 (처음 한 번만 계산한다. 고치지 말 것) */
    fun sortedOrder(): IntArray = order

    /** 다른 표와 Id 집합이 같은지 (같은 게임 버전) */
    fun sameIds(other: LangTable): Boolean {
        if (size != other.size) return false
        val a = sortedOrder()
        val b = other.sortedOrder()
        for (k in a.indices) if (ids[a[k]] != other.ids[b[k]]) return false
        return true
    }

    /**
     * 커뮤니티 한패와 같은 모양으로 쓴다: Id 오름차순 본문 + 청크 색인(청크 번호 내림차순).
     * 한패 파일로 파싱 → 재작성하면 원본과 바이트 단위로 같다.
     */
    fun toBytes(): ByteArray {
        val plan = plan()
        val out = ByteArrayOutputStream((4 + plan.header.size + plan.bodySize).toInt())
        BlockWriter(out, null).use { w ->
            w.u32le(plan.header.size); w.bytes(plan.header); writeBody(w, plan.order)
        }
        return out.toByteArray()
    }

    /**
     * [toBytes] 와 같은 내용을 파일로 쓰고 SHA-256(소문자 16진수)을 돌려준다.
     * 색인을 먼저 계산해서 한 번에 흘려 쓰고(임시 파일 없음), 해시도 쓰면서 같이 구한다 (다시 읽지 않음).
     */
    fun writeTo(file: File): String {
        file.parentFile?.mkdirs()
        val plan = plan()
        val md = MessageDigest.getInstance("SHA-256")
        FileOutputStream(file).use { fos ->
            BlockWriter(fos, md).use { w ->
                w.u32le(plan.header.size); w.bytes(plan.header); writeBody(w, plan.order)
            }
        }
        return hex(md.digest())
    }

    private class Plan(val order: IntArray, val header: ByteArray, val bodySize: Long)

    /** 쓰기 전에 줄마다 크기를 계산해 청크(Id/10)마다 offset·size 를 모은다. 같은 청크는 본문에서 연속이다. */
    private fun plan(): Plan {
        val order = sortedOrder()
        val cids = LongArrayBuilder()
        val offs = LongArrayBuilder()
        val lens = LongArrayBuilder()
        var pos = 0L
        var lastChunk = Long.MIN_VALUE
        var chunkStart = 0L
        for (i in order) {
            val id = ids[i]
            val inner = innerSize(id, texts.length(i))
            val cid = id / chunkSize
            if (cid != lastChunk) {
                if (lastChunk != Long.MIN_VALUE) lens.add(pos - chunkStart)
                cids.add(cid); offs.add(pos)
                lastChunk = cid; chunkStart = pos
            }
            pos += 1 + varintSize(inner.toLong()) + inner
        }
        if (lastChunk != Long.MIN_VALUE) lens.add(pos - chunkStart)
        return Plan(order, headerBytes(cids.toArray(), offs.toArray(), lens.toArray()), pos)
    }

    private fun writeBody(w: BlockWriter, order: IntArray) {
        val c = texts.cursor()
        for (i in order) {
            val id = ids[i]
            val n = texts.length(i)
            w.byte(0x0a); w.varint(innerSize(id, n).toLong())
            if (id != 0L) { w.byte(0x08); w.varint(id) }
            if (n > 0) { w.byte(0x12); w.varint(n.toLong()); c.load(i); w.bytes(c.bytes, n) }
        }
    }

    private fun innerSize(id: Long, n: Int): Int =
        (if (id != 0L) 1 + varintSize(id) else 0) + (if (n > 0) 1 + varintSize(n.toLong()) + n else 0)

    /** 색인: 상수 두 개 + 청크 목록(청크 번호 내림차순, 원본 한패와 같은 순서) */
    private fun headerBytes(cids: LongArray, offs: LongArray, lens: LongArray): ByteArray {
        val header = ByteArrayOutputStream(cids.size * 16 + 8)
        header.write(0x08); header.putVarint(chunkSize)
        header.write(0x10); header.putVarint(headerFlag)
        for (k in cids.indices.reversed()) {
            val cid = cids[k]; val off = offs[k]; val len = lens[k]
            val locLen = (if (off != 0L) 1 + varintSize(off) else 0) + (if (len != 0L) 1 + varintSize(len) else 0)
            val entLen = (if (cid != 0L) 1 + varintSize(cid) else 0) + 1 + varintSize(locLen.toLong()) + locLen
            header.write(0x1a); header.putVarint(entLen.toLong())
            if (cid != 0L) { header.write(0x08); header.putVarint(cid) }
            header.write(0x12); header.putVarint(locLen.toLong())
            if (off != 0L) { header.write(0x08); header.putVarint(off) }
            if (len != 0L) { header.write(0x10); header.putVarint(len) }
        }
        return header.toByteArray()
    }

    companion object {
        /**
         * 파일을 매핑하고 줄마다 Id 와 문장 자리(시작·길이)만 만든다. 문장은 복사하지 않아
         * 힙에는 줄마다 16바이트만 남는다 (49만 줄 표 하나에 8MB). 결과는 [parse] 와 같다.
         */
        fun read(file: File): LangTable = parse(Texts.map(file))

        /** 메모리에 올린 파일 내용을 읽는다 (시험용·작은 파일용. 앱은 [read] 로 매핑해 읽는다) */
        fun parse(b: ByteArray): LangTable = parse(ByteBuffer.wrap(b))

        private fun parse(b: ByteBuffer): LangTable {
            val size = b.capacity()
            require(size >= 4) { "파일이 너무 작음" }
            val r = BufReader(b, size)
            val headerLen = r.u32le()
            require(headerLen <= size - 4L) { "색인 길이가 파일보다 큼" }
            val bodyStart = 4 + headerLen.toInt()
            val (chunkSize, flag) = headerConstants(BufReader(b, bodyStart).also { it.p = 4 }, bodyStart)

            // 줄 수를 먼저 세어 배열을 딱 맞게 잡는다 (두 배씩 늘리며 복사하지 않게)
            val count = countEntries(b, bodyStart, size)
            val ids = LongArray(count)
            val offs = IntArray(count)
            val lens = IntArray(count)
            var k = 0
            r.p = bodyStart
            while (r.p < size) {
                val tag = r.varint()
                require(tag == 0x0aL) { "본문 태그 $tag @ ${r.p}" }
                val len = r.varint()
                require(len in 0..(size - r.p).toLong()) { "본문이 끊김" }
                val end = r.p + len.toInt()
                var id = 0L
                var off = 0
                var l = 0
                while (r.p < end) {
                    val t = r.varint()
                    when ((t and 7).toInt()) {
                        0 -> { val v = r.varint(); if (t ushr 3 == 1L) id = v }
                        2 -> {
                            val x = r.varint()
                            require(x in 0..(end - r.p).toLong()) { "문장이 끊김" }
                            if (t ushr 3 == 2L) { off = r.p; l = x.toInt() }
                            r.p += x.toInt()
                        }
                        else -> error("본문 wire type " + (t and 7))
                    }
                }
                // 안쪽 필드가 한 줄의 끝을 넘으면 깨진 파일 (C#·C++ 엔진과 같은 규칙)
                require(r.p == end) { "본문 한 줄이 제 길이를 넘음" }
                check(k < count) // 미리 센 곳에서 틀이 깨졌으면 위에서 이미 오류가 났다
                ids[k] = id; offs[k] = off; lens[k] = l; k++
            }
            return LangTable(ids, Texts(arrayOf(b), null, offs, lens), chunkSize, flag)
        }

        /** 본문 줄 수. 틀(태그·길이)이 깨진 곳에서 멈춘다 — 그 줄은 본 읽기가 같은 검사로 오류를 낸다 */
        private fun countEntries(b: ByteBuffer, start: Int, size: Int): Int {
            val r = BufReader(b, size)
            r.p = start
            var n = 0
            try {
                while (r.p < size) {
                    if (r.varint() != 0x0aL) break
                    val len = r.varint()
                    if (len < 0 || len > size - r.p) break
                    r.p += len.toInt()
                    n++
                }
            } catch (e: java.io.IOException) {
                // 끊긴 varint: 본 읽기가 같은 자리에서 오류를 낸다
            }
            return n
        }

        /** 색인 앞의 상수 두 개(청크 크기, 1)만 읽는다. 청크 목록은 쓸 때 다시 계산한다. */
        private fun headerConstants(r: BufReader, end: Int): Pair<Long, Long> {
            var chunkSize = 10L
            var flag = 1L
            while (r.p < end) {
                val tag = r.varint()
                when ((tag and 7).toInt()) {
                    0 -> { val v = r.varint(); if (tag ushr 3 == 1L) chunkSize = v else if (tag ushr 3 == 2L) flag = v }
                    2 -> {
                        // r.p += r.varint() 는 varint 가 p 를 옮기기 전 값을 더하므로 나눠 쓴다.
                        // 길이가 음수이거나 남은 것보다 크면 깨진 파일 (그대로 두면 p 가 뒤로 가 끝없이 돈다)
                        val len = r.varint()
                        require(len in 0..(end - r.p).toLong()) { "색인이 끊김" }
                        r.p += len.toInt()
                    }
                    else -> error("색인 wire type " + (tag and 7))
                }
            }
            require(r.p == end) { "색인이 제 길이를 넘음" }
            return chunkSize to flag
        }

        /** 본문의 Id 만 읽는다. 문장은 건너뛰어 메모리를 거의 쓰지 않는다 (버전 지문용). */
        fun readIds(file: File): LongArray = file.inputStream().use { readIds(it, file.length()) }

        /**
         * size: 스트림 전체 크기. 길이 값이 남은 크기를 넘으면 깨진 파일로 본다
         * (InputStream.skip 은 파일 끝을 넘어가도 조용히 성공해서, 확인하지 않으면 잘린 파일도 일부 Id 로 지문을 만든다)
         */
        fun readIds(input: InputStream, size: Long): LongArray {
            val s = StreamReader(input)
            val headerLen = s.u32le()
            require(headerLen <= size - 4) { "색인 길이가 파일보다 큼" }
            s.skip(headerLen)
            val ids = LongArrayBuilder()
            while (true) {
                val tag = s.varintOrEof() ?: break
                require(tag == 0x0aL) { "본문 태그 $tag" }
                val len = s.varint()
                require(len in 0..(size - s.pos)) { "본문이 끊김" }
                val end = s.pos + len
                var id = 0L
                while (s.pos < end) {
                    val t = s.varint()
                    when ((t and 7).toInt()) {
                        0 -> { val v = s.varint(); if (t ushr 3 == 1L) id = v }
                        2 -> {
                            val l = s.varint()
                            require(l in 0..(end - s.pos)) { "문장이 끊김" }
                            s.skip(l)
                        }
                        else -> error("본문 wire type " + (t and 7))
                    }
                }
                require(s.pos == end) { "본문 한 줄이 제 길이를 넘음" }
                ids.add(id)
            }
            return ids.toArray()
        }

        /**
         * 게임 버전 구분용 지문. Id 는 업데이트마다 전부 다시 매겨지므로 Id 집합이 같으면 같은 버전의 표다.
         * 공식 원문과 그 버전용 한패는 지문이 같다.
         */
        fun layoutKeyOf(ids: LongArray): String {
            val sorted = ids.copyOf().also { it.sort() }
            return sorted.size.toString() + ":" + digest16(sorted)
        }

        /**
         * 색인(파일 앞 1MB 안쪽)만으로 구하는 버전 지문: 청크 번호(Id/10) 집합.
         * Id 집합이 같으면 청크 번호 집합도 같다. GitHub 이력에서 옛 버전 한패를 찾을 때
         * 파일 전체(50MB) 대신 앞부분만 받아 비교하는 데 쓴다.
         */
        fun chunkKeyOf(header: ByteArray): String {
            val r = Reader(header)
            val cids = LongArrayBuilder()
            while (r.p < header.size) {
                val tag = r.varint()
                when ((tag and 7).toInt()) {
                    0 -> r.varint()
                    2 -> {
                        val len = r.varint()
                        require(len in 0..(header.size - r.p).toLong()) { "색인이 끊김" }
                        val end = r.p + len.toInt()
                        if (tag ushr 3 == 3L) {
                            var cid = 0L
                            while (r.p < end) {
                                val t = r.varint()
                                if ((t and 7).toInt() == 0) { val v = r.varint(); if (t ushr 3 == 1L) cid = v }
                                else {
                                    val l = r.varint()
                                    require(l in 0..(end - r.p).toLong()) { "색인이 끊김" }
                                    r.p += l.toInt()
                                }
                            }
                            cids.add(cid)
                        }
                        r.p = end
                    }
                    else -> error("색인 wire type " + (tag and 7))
                }
            }
            val sorted = cids.toArray().also { it.sort() }
            return sorted.size.toString() + ":" + digest16(sorted)
        }

        /**
         * 본문 앞 maxBytes 안의 한글 음절 수. 공식 중섭 파일은 0 이고 한패는 대부분 한글이라 둘을 가른다.
         * 읽을 수 없으면 -1.
         */
        fun hangulCount(file: File, maxBytes: Int): Int = try {
            RandomAccessFile(file, "r").use { f ->
                val head = ByteArray(4)
                f.readFully(head)
                val headerLen = (head[0].toLong() and 0xff) or ((head[1].toLong() and 0xff) shl 8) or
                    ((head[2].toLong() and 0xff) shl 16) or ((head[3].toLong() and 0xff) shl 24)
                val start = 4L + headerLen
                if (start >= f.length()) return -1
                f.seek(start)
                val buf = ByteArray(minOf(maxBytes.toLong(), f.length() - start).toInt())
                f.readFully(buf)
                // 한글 음절 U+AC00..U+D7A3 은 UTF-8 로 EA B0 80 ~ ED 9E A3
                var n = 0
                var i = 0
                while (i + 2 < buf.size) {
                    val b0 = buf[i].toInt() and 0xff
                    val b1 = buf[i + 1].toInt() and 0xff
                    val b2 = buf[i + 2].toInt() and 0xff
                    if (b0 in 0xEA..0xED && b1 and 0xC0 == 0x80 && b2 and 0xC0 == 0x80) {
                        val cp = ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
                        if (cp in 0xAC00..0xD7A3) { n++; i += 3; continue }
                    }
                    i++
                }
                n
            }
        } catch (t: Throwable) {
            -1
        }

        /** 파일의 색인 부분만 읽어 [chunkKeyOf] */
        fun chunkKey(file: File): String = file.inputStream().buffered(1 shl 16).use { input ->
            val s = StreamReader(input)
            val header = ByteArray(s.u32le().toInt())
            s.readFully(header)
            chunkKeyOf(header)
        }

        private fun digest16(sorted: LongArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8 shl 13)
            var k = 0
            for (v in sorted) {
                for (i in 0 until 8) buf[k + i] = (v ushr (8 * i)).toByte()
                k += 8
                if (k == buf.size) { md.update(buf); k = 0 }
            }
            md.update(buf, 0, k)
            return hex(md.digest()).take(16)
        }

        /**
         * Id 오름차순 위치. 한패 파일은 이미 오름차순이라 그대로 쓰고, 아니면(공식 원문)
         * Id 와 위치를 long 하나에 담아 기본형 정렬한다 (Id < 2^42, 줄 < 2^21 일 때. 아니면 일반 정렬).
         */
        internal fun sortedOrderOf(ids: LongArray): IntArray {
            val n = ids.size
            var sorted = true
            var packable = n < (1 shl 21)
            for (i in 0 until n) {
                if (i > 0 && ids[i] < ids[i - 1]) sorted = false
                if (ids[i] < 0 || ids[i] >= (1L shl 42)) packable = false
            }
            if (sorted) return IntArray(n) { it }
            if (!packable) return (0 until n).sortedBy { ids[it] }.toIntArray()
            val packed = LongArray(n) { (ids[it] shl 21) or it.toLong() }
            packed.sort()
            return IntArray(n) { (packed[it] and 0x1FFFFFL).toInt() }
        }

        fun hex(bytes: ByteArray): String {
            val c = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xff
                c[2 * i] = HEX[v ushr 4]; c[2 * i + 1] = HEX[v and 15]
            }
            return String(c)
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

internal class Reader(private val b: ByteArray) {
    var p = 0
    fun varint(): Long {
        var r = 0L
        var s = 0
        while (true) {
            if (p >= b.size) throw EOFException("파일이 중간에 끊김")
            val x = b[p++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl s)
            if (x < 0x80) return r
            s += 7
            if (s > 63) throw java.io.IOException("varint 가 너무 김")
        }
    }
}

/**
 * InputStream 위의 protobuf 읽기. 읽은 바이트 수(pos)를 센다.
 * 64KB 씩 직접 받아 두고 바이트 배열에서 읽는다 (InputStream.read() 를 바이트마다 부르면 몇 배 느리다).
 */
internal class StreamReader(private val input: InputStream) {
    private val buf = ByteArray(1 shl 16)
    private var p = 0
    private var lim = 0
    var pos = 0L
        private set

    private fun fill(): Boolean {
        val n = input.read(buf, 0, buf.size)
        if (n <= 0) return false
        p = 0
        lim = n
        return true
    }

    private fun byte(): Int {
        if (p == lim && !fill()) throw EOFException()
        pos++
        return buf[p++].toInt() and 0xff
    }

    fun varintOrEof(): Long? {
        if (p == lim && !fill()) return null
        var r = 0L
        var s = 0
        while (true) {
            val x = byte()
            r = r or ((x and 0x7f).toLong() shl s)
            if (x < 0x80) return r
            s += 7
        }
    }

    fun varint(): Long = varintOrEof() ?: throw EOFException()

    fun u32le(): Long = byte().toLong() or (byte().toLong() shl 8) or (byte().toLong() shl 16) or (byte().toLong() shl 24)

    fun skip(n: Long) {
        var left = n
        while (left > 0) {
            if (p < lim) {
                val k = minOf(left, (lim - p).toLong()).toInt()
                p += k; pos += k; left -= k
            } else {
                val k = input.skip(left)
                if (k > 0) { pos += k; left -= k } else if (!fill()) throw EOFException()
            }
        }
    }

    fun readFully(dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            if (p == lim && !fill()) throw EOFException()
            val k = minOf(dst.size - off, lim - p)
            System.arraycopy(buf, p, dst, off, k)
            p += k; off += k
        }
        pos += dst.size
    }
}

/**
 * 64KB 블록 단위로 모아 쓰고, 해시가 있으면 블록마다 같이 갱신한다
 * (DigestOutputStream 은 바이트마다 해시를 불러 느리다).
 */
private class BlockWriter(private val out: OutputStream, private val md: MessageDigest?) : java.io.Closeable {
    private val buf = ByteArray(1 shl 16)
    private var n = 0

    private fun flushBlock() {
        if (n == 0) return
        out.write(buf, 0, n)
        md?.update(buf, 0, n)
        n = 0
    }

    fun byte(b: Int) {
        if (n == buf.size) flushBlock()
        buf[n++] = b.toByte()
    }

    fun varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            byte(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        byte(v.toInt())
    }

    fun u32le(v: Int) { byte(v); byte(v ushr 8); byte(v ushr 16); byte(v ushr 24) }

    fun bytes(b: ByteArray, len: Int = b.size) {
        var off = 0
        while (off < len) {
            if (n == buf.size) flushBlock()
            val k = minOf(len - off, buf.size - n)
            System.arraycopy(b, off, buf, n, k)
            n += k; off += k
        }
    }

    override fun close() {
        flushBlock()
        out.flush()
    }
}

internal class LongArrayBuilder {
    private var a = LongArray(1 shl 16)
    private var n = 0
    fun add(v: Long) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun toArray(): LongArray = a.copyOf(n)
}

internal fun OutputStream.putVarint(value: Long) {
    var v = value
    while (v and 0x7fL.inv() != 0L) {
        write(((v and 0x7f) or 0x80).toInt())
        v = v ushr 7
    }
    write(v.toInt())
}

internal fun varintSize(value: Long): Int {
    var v = value
    var n = 1
    while (v and 0x7fL.inv() != 0L) { n++; v = v ushr 7 }
    return n
}
