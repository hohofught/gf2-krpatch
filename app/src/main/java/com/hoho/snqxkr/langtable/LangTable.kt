package com.hoho.snqxkr.langtable

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * LangPackageTable*.bytes 한 파일. 형식은 docs/lang-table-format.md 참고.
 *
 *  [u32 LE 색인 길이][색인 protobuf][본문 protobuf]
 *  본문: repeated TextMap { int64 Id = 1; string Content = 2 }
 *  색인: 1 = 청크 크기(10), 2 = 1, 3 = repeated { 청크 번호(Id/10), { 본문 내 offset, size } }
 *
 * 문장은 UTF-8 바이트 그대로 들고 있는다 (String 으로 바꾸면 메모리가 두 배 든다).
 * 빈 문장은 빈 배열이다 (proto3 는 빈 문자열 필드를 생략한다).
 */
class LangTable(
    val ids: LongArray,
    val texts: Array<ByteArray>,
    val chunkSize: Long = 10,
    val headerFlag: Long = 1,
) {
    init {
        require(ids.size == texts.size)
    }

    val size: Int get() = ids.size

    /** 게임 버전 구분용 지문 ([layoutKeyOf]) */
    fun layoutKey(): String = layoutKeyOf(ids)

    /** Id 오름차순으로 늘어선 위치 */
    fun sortedOrder(): IntArray = (0 until size).sortedBy { ids[it] }.toIntArray()

    /**
     * 커뮤니티 한패와 같은 모양으로 쓴다: Id 오름차순 본문 + 청크 색인(청크 번호 내림차순).
     * 한패 파일로 파싱 → 재작성하면 원본과 바이트 단위로 같다.
     */
    fun toBytes(): ByteArray {
        val body = ByteArrayOutputStream(texts.sumOf { it.size + 16 })
        val header = headerBytes(writeBody(body))
        val out = ByteArrayOutputStream(4 + header.size + body.size())
        out.writeU32le(header.size)
        out.write(header)
        body.writeTo(out)
        return out.toByteArray()
    }

    /** [toBytes] 와 같은 내용을 파일로. 본문을 임시 파일로 흘려 써서 메모리를 적게 쓴다. */
    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        val bodyTmp = File(file.parentFile, file.name + ".body")
        try {
            val chunks = bodyTmp.outputStream().buffered(1 shl 16).use { writeBody(it) }
            val header = headerBytes(chunks)
            file.outputStream().buffered(1 shl 16).use { out ->
                out.writeU32le(header.size)
                out.write(header)
                bodyTmp.inputStream().use { it.copyTo(out, 1 shl 16) }
            }
        } finally {
            bodyTmp.delete()
        }
    }

    private class Chunks {
        val ids = LongArrayBuilder()
        val offsets = LongArrayBuilder()
        val sizes = LongArrayBuilder()
    }

    /** Id 오름차순으로 본문을 쓰며 청크(Id/10)마다 offset·size 를 모은다. 같은 청크는 본문에서 연속이다. */
    private fun writeBody(sink: OutputStream): Chunks {
        val out = CountingOutput(sink)
        val chunks = Chunks()
        var lastChunk = Long.MIN_VALUE
        var chunkStart = 0L
        for (i in sortedOrder()) {
            val id = ids[i]
            val text = texts[i]
            val inner = (if (id != 0L) 1 + varintSize(id) else 0) +
                (if (text.isNotEmpty()) 1 + varintSize(text.size.toLong()) + text.size else 0)
            val cid = id / chunkSize
            if (cid != lastChunk) {
                if (lastChunk != Long.MIN_VALUE) chunks.sizes.add(out.count - chunkStart)
                chunks.ids.add(cid); chunks.offsets.add(out.count)
                lastChunk = cid; chunkStart = out.count
            }
            out.write(0x0a); out.putVarint(inner.toLong())
            if (id != 0L) { out.write(0x08); out.putVarint(id) }
            if (text.isNotEmpty()) { out.write(0x12); out.putVarint(text.size.toLong()); out.write(text) }
        }
        if (lastChunk != Long.MIN_VALUE) chunks.sizes.add(out.count - chunkStart)
        out.flush()
        return chunks
    }

    /** 색인: 상수 두 개 + 청크 목록(청크 번호 내림차순, 원본 한패와 같은 순서) */
    private fun headerBytes(chunks: Chunks): ByteArray {
        val cids = chunks.ids.toArray(); val offs = chunks.offsets.toArray(); val lens = chunks.sizes.toArray()
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
        fun read(file: File): LangTable = parse(file.readBytes())

        fun parse(b: ByteArray): LangTable {
            val r = Reader(b)
            val headerLen = (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
                ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
            val bodyStart = 4 + headerLen.toInt()
            require(headerLen >= 0 && bodyStart <= b.size) { "색인 길이가 파일보다 큼" }

            // 색인 앞의 상수 두 개만 읽는다 (청크 목록은 쓸 때 다시 계산한다)
            var chunkSize = 10L
            var flag = 1L
            r.p = 4
            while (r.p < bodyStart) {
                val tag = r.varint()
                when ((tag and 7).toInt()) {
                    0 -> { val v = r.varint(); if (tag ushr 3 == 1L) chunkSize = v else if (tag ushr 3 == 2L) flag = v }
                    2 -> {
                        // r.p += r.varint() 는 varint 가 p 를 옮기기 전 값을 더하므로 나눠 쓴다
                        val len = r.varint().toInt()
                        r.p += len
                    }
                    else -> error("색인 wire type " + (tag and 7))
                }
            }

            val ids = LongArrayBuilder()
            val texts = ArrayList<ByteArray>()
            r.p = bodyStart
            while (r.p < b.size) {
                val tag = r.varint()
                require(tag == 0x0aL) { "본문 태그 $tag @ ${r.p}" }
                val len = r.varint().toInt()
                val end = r.p + len
                var id = 0L
                var text = EMPTY
                while (r.p < end) {
                    val t = r.varint()
                    when ((t and 7).toInt()) {
                        0 -> { val v = r.varint(); if (t ushr 3 == 1L) id = v }
                        2 -> {
                            val l = r.varint().toInt()
                            if (t ushr 3 == 2L) text = b.copyOfRange(r.p, r.p + l)
                            r.p += l
                        }
                        else -> error("본문 wire type " + (t and 7))
                    }
                }
                ids.add(id); texts.add(text)
                r.p = end
            }
            return LangTable(ids.toArray(), texts.toTypedArray(), chunkSize, flag)
        }

        /** 본문의 Id 만 읽는다. 문장은 건너뛰어 메모리를 거의 쓰지 않는다 (버전 지문용). */
        fun readIds(file: File): LongArray = file.inputStream().buffered(1 shl 16).use { readIds(it) }

        fun readIds(input: InputStream): LongArray {
            val s = StreamReader(input)
            s.skip(s.u32le())
            val ids = LongArrayBuilder()
            while (true) {
                val tag = s.varintOrEof() ?: break
                require(tag == 0x0aL) { "본문 태그 $tag" }
                val len = s.varint()
                val end = s.pos + len
                var id = 0L
                while (s.pos < end) {
                    val t = s.varint()
                    when ((t and 7).toInt()) {
                        0 -> { val v = s.varint(); if (t ushr 3 == 1L) id = v }
                        2 -> { val l = s.varint(); s.skip(l) }
                        else -> error("본문 wire type " + (t and 7))
                    }
                }
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
                        val len = r.varint().toInt()
                        val end = r.p + len
                        if (tag ushr 3 == 3L) {
                            var cid = 0L
                            while (r.p < end) {
                                val t = r.varint()
                                if ((t and 7).toInt() == 0) { val v = r.varint(); if (t ushr 3 == 1L) cid = v }
                                else { val l = r.varint().toInt(); r.p += l }
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
            val buf = ByteArray(8)
            for (v in sorted) {
                for (i in 0 until 8) buf[i] = (v ushr (8 * i)).toByte()
                md.update(buf)
            }
            return md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }

        private val EMPTY = ByteArray(0)
    }
}

internal class Reader(private val b: ByteArray) {
    var p = 0
    fun varint(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val x = b[p++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl s)
            if (x < 0x80) return r
            s += 7
        }
    }
}

/** InputStream 위의 protobuf 읽기. 읽은 바이트 수(pos)를 센다. */
internal class StreamReader(private val input: InputStream) {
    var pos = 0L
        private set

    private fun byte(): Int {
        val x = input.read()
        if (x < 0) throw EOFException()
        pos++
        return x
    }

    fun varintOrEof(): Long? {
        val first = input.read()
        if (first < 0) return null
        pos++
        var r = (first and 0x7f).toLong()
        if (first < 0x80) return r
        var s = 7
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
            val k = input.skip(left)
            if (k > 0) { left -= k; pos += k } else { byte(); left-- }
        }
    }

    fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val k = input.read(buf, off, buf.size - off)
            if (k < 0) throw EOFException()
            off += k
        }
        pos += buf.size
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

private class CountingOutput(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    override fun flush() = out.flush()
}

internal fun OutputStream.putVarint(value: Long) {
    var v = value
    while (v and 0x7fL.inv() != 0L) {
        write(((v and 0x7f) or 0x80).toInt())
        v = v ushr 7
    }
    write(v.toInt())
}

private fun OutputStream.writeU32le(v: Int) {
    write(v and 0xff); write((v ushr 8) and 0xff); write((v ushr 16) and 0xff); write((v ushr 24) and 0xff)
}

internal fun varintSize(value: Long): Int {
    var v = value
    var n = 1
    while (v and 0x7fL.inv() != 0L) { n++; v = v ushr 7 }
    return n
}
