package com.hoho.snqxkr.langtable

import java.io.DataOutputStream
import java.io.File

/**
 * 중국어 원문 → 한국어 번역.
 *
 * 게임 업데이트마다 Id 가 전부 다시 매겨지므로 Id 가 아니라 원문으로 찾는다.
 * 원문은 64비트 해시로만 들고 있다 (충돌 확률 약 10^-9). 번역은 복사하지 않고 매핑한 파일 안의 자리만 든다 ([Texts]).
 * 한패는 같은 원문을 항상 같은 번역으로 옮기므로(08월 버전 실측: 갈리는 원문 0개) 원문 하나에 번역 하나면 된다.
 *
 * 줄마다 세대(몇 번째로 합친 한패에서 마지막으로 봤는지)를 들고 있다. 파일이 [MAX_BYTES] 를 넘으면
 * 가장 오래전에 본 줄(게임에서 사라진 문장일 가능성이 큰 것)부터 뺀다.
 */
class TranslationMemory private constructor(
    private val keys: LongArray,       // 오름차순
    private val values: Texts,
    private val gens: IntArray,
) {
    val size: Int get() = keys.size

    /** 번역 문장들 (줄 번호는 [find] 가 돌려준 것) */
    internal val texts: Texts get() = values

    /** 파일로 썼을 때 크기 */
    val byteSize: Long
        get() {
            var total = HEADER_BYTES
            for (i in 0 until size) total += ENTRY_BYTES + values.length(i)
            return total
        }

    /** 원문 해시([hash])로 찾은 줄 번호. 없으면 음수 */
    fun find(hash: Long): Int = keys.binarySearch(hash)

    /** 원문으로 찾은 번역 (복사본. 시험·작은 곳용) */
    operator fun get(source: ByteArray): ByteArray? {
        val i = find(hash(source))
        return if (i >= 0) values[i] else null
    }

    /** 두 메모리를 합친다. 같은 원문이면 newer 의 번역이 이기고, newer 의 줄은 새 세대가 된다. */
    fun mergedWith(newer: TranslationMemory): TranslationMemory {
        val gen = (gens.maxOrNull() ?: -1) + 1
        val n = size + newer.size
        val k = LongArray(n)
        val v = Texts.Builder(listOf(values, newer.values), n)
        val g = IntArray(n)
        var i = 0
        var j = 0
        var o = 0
        while (i < size || j < newer.size) {
            val takeNew = i >= size || (j < newer.size && newer.keys[j] <= keys[i])
            if (takeNew) {
                if (i < size && keys[i] == newer.keys[j]) i++
                k[o] = newer.keys[j]; v.set(o, 1, newer.values, j); g[o] = gen; j++
            } else {
                k[o] = keys[i]; v.set(o, 0, values, i); g[o] = gens[i]; i++
            }
            o++
        }
        return TranslationMemory(k.copyOf(o), v.build(o), g.copyOf(o))
    }

    /** 파일 크기가 maxBytes 이하가 되도록 오래된 세대부터 뺀다 */
    fun capped(maxBytes: Long = MAX_BYTES): TranslationMemory {
        var total = byteSize
        if (total <= maxBytes) return this
        val drop = BooleanArray(size)
        for (gen in gens.distinct().sorted()) {
            for (i in keys.indices) {
                if (total <= maxBytes) break
                if (gens[i] == gen) { drop[i] = true; total -= ENTRY_BYTES + values.length(i) }
            }
            if (total <= maxBytes) break
        }
        val keep = keys.indices.filter { !drop[it] }.toIntArray()
        return TranslationMemory(LongArray(keep.size) { keys[keep[it]] }, values.select(keep), IntArray(keep.size) { gens[keep[it]] })
    }

    fun writeTo(file: File) {
        val c = values.cursor()
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
            out.writeInt(MAGIC_V2)
            out.writeInt(size)
            for (i in keys.indices) {
                val n = c.load(i)
                out.writeLong(keys[i])
                out.writeInt(gens[i])
                out.writeInt(n)
                out.write(c.bytes, 0, n)
            }
        }
    }

    companion object {
        private const val MAGIC_V1 = 0x534e514d // "SNQM": 세대 없음
        private const val MAGIC_V2 = 0x534e5132 // "SNQ2"
        private const val HEADER_BYTES = 8L
        private const val ENTRY_BYTES = 16L     // 해시 8 + 세대 4 + 길이 4

        /** 번역 메모리 파일 최대 크기. 지금 속도(업데이트마다 1~2MB)로는 몇 년 걸린다. */
        const val MAX_BYTES = 500L * 1024 * 1024

        /**
         * 같은 게임 버전의 공식 원문과 한패를 같은 Id 끼리 짝지어 만든다.
         * 한패가 원문을 그대로 둔 문장(미번역, 숫자·기호)은 넣지 않는다. 번역은 한패 파일 안의 자리로 든다.
         */
        fun build(official: LangTable, patch: LangTable): TranslationMemory {
            require(official.sameIds(patch)) { "공식 원문과 한패의 게임 버전이 다릅니다" }
            val n = official.size
            // 공식 원문 i 번째 줄과 같은 Id 인 한패 줄 (둘 다 Id 순으로 늘어놓고 짝짓는다)
            val oOrd = official.sortedOrder()
            val pOrd = patch.sortedOrder()
            val patchAt = IntArray(n)
            for (k in 0 until n) patchAt[oOrd[k]] = pOrd[k]
            val hashes = LongArray(n)
            val src = IntArray(n)
            val zh = official.texts.cursor()
            val ko = patch.texts.cursor()
            var m = 0
            for (i in 0 until n) {
                if (zh.same(i, ko, patchAt[i])) continue
                val l = zh.load(i)
                hashes[m] = hash(zh.bytes, l); src[m] = patchAt[i]; m++
            }
            // 해시 순으로 정렬하되 같은 원문은 공식 원문에서 먼저 나온 줄의 번역을 쓴다 (안정 정렬)
            radixSort(hashes, src, m)
            var u = 0
            for (i in 0 until m) {
                if (u > 0 && hashes[i] == hashes[u - 1]) continue
                hashes[u] = hashes[i]; src[u] = src[i]; u++
            }
            return TranslationMemory(hashes.copyOf(u), patch.texts.select(src, u), IntArray(u))
        }

        /** 64비트 키(부호 있는 순서)로 안정 정렬, 값은 따라 움직인다. 8비트씩 8번 도는 기수 정렬. */
        internal fun radixSort(keys: LongArray, vals: IntArray, n: Int) {
            var k1 = keys; var v1 = vals
            var k2 = LongArray(n); var v2 = IntArray(n)
            val count = IntArray(257)
            for (shift in 0 until 64 step 8) {
                count.fill(0)
                for (i in 0 until n) count[(((k1[i] xor Long.MIN_VALUE) ushr shift) and 0xff).toInt() + 1]++
                if (count.any { it == n }) continue // 이 자리 숫자가 모두 같으면 건너뛴다
                for (b in 0 until 256) count[b + 1] += count[b]
                for (i in 0 until n) {
                    val d = (((k1[i] xor Long.MIN_VALUE) ushr shift) and 0xff).toInt()
                    val at = count[d]++
                    k2[at] = k1[i]; v2[at] = v1[i]
                }
                val tk = k1; k1 = k2; k2 = tk
                val tv = v1; v1 = v2; v2 = tv
            }
            if (k1 !== keys) {
                System.arraycopy(k1, 0, keys, 0, n)
                System.arraycopy(v1, 0, vals, 0, n)
            }
        }

        /** 파일을 매핑해 읽는다. 번역은 복사하지 않고 파일 안의 자리만 든다 (27만 줄에 힙 5MB 안팎). */
        fun read(file: File): TranslationMemory {
            val b = Texts.map(file)
            val size = b.capacity()
            val r = BufReader(b, size)
            val magic = r.u32be()
            require(magic == MAGIC_V1 || magic == MAGIC_V2) { "번역 메모리 파일이 아닙니다" }
            val n = r.u32be()
            // 줄마다 12바이트 이상이므로 파일 크기로 줄 수를 확인한다 (깨진 파일에 큰 배열을 잡지 않게)
            require(n >= 0 && n <= (size - HEADER_BYTES) / 12) { "번역 메모리 줄 수가 이상함" }
            val keys = LongArray(n)
            val gens = IntArray(n)
            val off = IntArray(n)
            val len = IntArray(n)
            var left = size - HEADER_BYTES
            for (i in 0 until n) {
                keys[i] = r.u64be()
                if (magic == MAGIC_V2) gens[i] = r.u32be()
                val l = r.u32be()
                left -= (if (magic == MAGIC_V2) ENTRY_BYTES else 12L) + l
                require(l >= 0 && left >= 0) { "번역 메모리 파일이 끊김" }
                off[i] = r.p; len[i] = l
                r.p += l
            }
            return TranslationMemory(keys, Texts(arrayOf(b), null, off, len), gens)
        }

        /** FNV-1a 64 + murmur3 fmix64 (bytes 의 앞 n 바이트) */
        fun hash(bytes: ByteArray, n: Int = bytes.size): Long {
            var h = -0x340d631b7bdddcdbL
            for (k in 0 until n) {
                h = h xor (bytes[k].toLong() and 0xff)
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33); h *= -0xae502812aa7333L
            h = h xor (h ushr 33); h *= -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }
    }
}
