package com.hoho.snqxkr.langtable

/**
 * 게임 업데이트로 한패가 맞지 않게 됐을 때, 새 공식 원문을 다시 한국어로 만든다.
 *  1단계: 번역 메모리에서 문장을 원문으로 찾아 바꾼다. 메모리에 없는 문장(새 콘텐츠)은 중국어로 둔다.
 *  2단계: 업데이트 전에 쓰던 한패가 메모리보다 새것이면, 그 번역을 새 Id 자리로 옮긴다 ([overlay]).
 * 결과 문장은 복사하지 않고 원문·메모리·옛 한패 파일 안의 자리로 든다 ([Texts.Builder]).
 * 색인은 LangTable 이 쓸 때 다시 계산한다.
 */
object PatchRepair {

    data class Result(
        val table: LangTable,
        val total: Int,         // 전체 문장
        val translated: Int,    // 한국어가 된 문장 (번역 메모리 + 옛 한패)
        val leftChinese: Int,   // 한자가 있는데 한국어로 못 바꿔 중국어로 남은 문장
        val fromPatch: Int = 0, // 그중 옛 한패에서 옮긴 문장 (메모리에 없던 것, 메모리보다 새 표현)
    ) {
        val coverage: Double get() = translated.toDouble() / (translated + leftChinese).coerceAtLeast(1)
    }

    // 결과 문장이 어디서 왔는지 (Texts.Builder 의 parts 순서)
    private const val OFFICIAL = 0
    private const val MEMORY = 1
    private const val OLD_PATCH = 2

    fun repair(official: LangTable, tm: TranslationMemory, oldPatch: LangTable? = null): Result {
        val n = official.size
        val zh = official.texts
        val out = Texts.Builder(listOfNotNull(zh, tm.texts, oldPatch?.texts), n)
        val zc = zh.cursor()
        val mc = tm.texts.cursor()
        // 2단계용: Id 순 k 번째 줄의 1단계 결과 키. 파일 순서로 읽으며(순차 읽기가 빠르다) 바로 Id 순 자리에 쓴다.
        // 원문 그대로인 줄은 찾을 때 구한 해시를 다시 쓴다
        val keys = if (oldPatch != null) LongArray(n) else null
        val rank = if (keys != null) official.sortedOrder().let { o -> IntArray(n).also { r -> for (k in 0 until n) r[o[k]] = k } } else null
        val han = BooleanArray(n) // 원문 그대로 둔 줄에 한자가 있는지
        for (i in 0 until n) {
            val k = rank?.get(i) ?: 0
            val l = zc.load(i)
            val h = TranslationMemory.hash(zc.bytes, l)
            val j = tm.find(h)
            if (j >= 0) {
                out.set(i, MEMORY, tm.texts, j)
                if (keys != null) keys[k] = key(mc, j)
            } else {
                out.set(i, OFFICIAL, zh, i)
                han[i] = hasHan(zc.bytes, l)
                if (keys != null) keys[k] = if (l == 0) 0L else nonZero(h)
            }
        }
        val fromPatch = if (oldPatch != null && keys != null) overlay(official, out, keys, oldPatch) else 0
        var translated = 0
        var leftChinese = 0
        for (i in 0 until n) {
            when {
                !out.isFrom(i, OFFICIAL, zh) -> translated++
                han[i] -> leftChinese++
            }
        }
        val table = LangTable(official.ids, out.build(), official.chunkSize, official.headerFlag)
        return Result(table, n, translated, leftChinese, fromPatch)
    }

    /**
     * 2단계. Id 는 업데이트마다 다시 매겨지지만 연속된 몇 줄(대사 한 장면 등)이 함께 움직인다.
     * 1단계 결과(out)와 옛 한패를 Id 순으로 늘어놓고, 같은 문장이 3줄 이상 이어지는 곳에서
     * 옛 줄 → 새 줄 대응을 정한다. 후보가 여럿이면 가장 길게 이어지는 한 곳만 쓰고, 동률이면 버린다.
     * 대응된 두 줄 사이가 양쪽 모두 끊김 없이 같은 길이면 그 사이 줄도 같은 차이로 옮긴다.
     * 옮길 번역이 새 원문과 태그·자리표시자·숫자가 다르면 쓰지 않는다 (예: 150% → 180% 로 바뀐 스킬 설명).
     *
     * 메모리가 이미 이 한패를 담고 있으면 얻는 게 없고 바뀐 원문에 옛 번역을 얹게 되므로 부르지 않는다.
     * 실측 (메모리 V4 + 옛 한패 V2 → V1): 실제 V1 한패와 같은 줄 437,988 → 443,007, 옮긴 줄의 98% 가 V1 한패와 같음.
     *
     * Id 는 복사하지 않고 "Id 순 다음 줄과 1 차이인지" 만 1바이트로 든다.
     *
     * @param nh Id 순 b 번째 줄의 1단계 결과 문장 키 ([key])
     * @return out 에서 바꾼 줄 수
     */
    private fun overlay(official: LangTable, out: Texts.Builder, nh: LongArray, oldPatch: LangTable): Int {
        val nOrd = official.sortedOrder()
        val oOrd = oldPatch.sortedOrder()
        val N = nOrd.size
        val O = oOrd.size
        val nNext = BooleanArray(N) { it + 1 < N && official.ids[nOrd[it + 1]] == official.ids[nOrd[it]] + 1 }
        val oNext = BooleanArray(O) { it + 1 < O && oldPatch.ids[oOrd[it + 1]] == oldPatch.ids[oOrd[it]] + 1 }
        val oc = oldPatch.texts.cursor()
        val oh = LongArray(O) { key(oc, oOrd[it]) }

        // 새 쪽 문장 색인: 해시 상위 비트에 위치를 붙여 정렬 (HashMap 없이)
        val posBits = 64 - java.lang.Long.numberOfLeadingZeros(nh.size.toLong().coerceAtLeast(1))
        val low = (1L shl posBits) - 1
        val packed = LongArray(nh.size) { (nh[it] and low.inv()) or it.toLong() }.also { it.sort() }

        fun nextOk(a: Int, b: Int) = a + 1 < O && b + 1 < N && oNext[a] && nNext[b] && oh[a + 1] != 0L && oh[a + 1] == nh[b + 1]
        fun prevOk(a: Int, b: Int) = a > 0 && b > 0 && oNext[a - 1] && nNext[b - 1] && oh[a - 1] != 0L && oh[a - 1] == nh[b - 1]

        // 옛 줄마다 가장 길게 이어지는 새 자리
        val matchTo = IntArray(O) { -1 }
        for (a in 0 until O) {
            val h = oh[a]
            if (h == 0L) continue
            val hi = h and low.inv()
            var k = lowerBound(packed, hi)
            val first = k
            while (k < packed.size && (packed[k] and low.inv()) == hi) k++
            if (k - first > MAX_CANDIDATES) continue
            var best = 0
            var second = 0
            var bestB = -1
            for (c in first until k) {
                val b = (packed[c] and low).toInt()
                if (nh[b] != h) continue
                var run = 1
                var x = a; var y = b
                while (run < MAX_RUN && prevOk(x, y)) { x--; y--; run++ }
                x = a; y = b
                while (run < 2 * MAX_RUN && nextOk(x, y)) { x++; y++; run++ }
                if (run > best) { second = best; best = run; bestB = b } else if (run > second) second = run
            }
            if (best >= MIN_RUN && best > second) matchTo[a] = bestB
        }

        // 새 자리 → 옛 줄. 두 줄이 한 자리로 오면 둘 다 쓰지 않는다.
        val target = IntArray(N) { -1 }
        fun put(a: Int, b: Int) { target[b] = if (target[b] == -1) a else -2 }
        var last = -1
        for (a in 0 until O) {
            val b = matchTo[a]
            if (b < 0) continue
            put(a, b)
            if (last >= 0) {
                val lb = matchTo[last]
                val gap = a - last
                if (gap > 1 && b - lb == gap && oldPatch.ids[oOrd[a]] - oldPatch.ids[oOrd[last]] == gap.toLong() &&
                    official.ids[nOrd[b]] - official.ids[nOrd[lb]] == gap.toLong()
                ) {
                    for (t in 1 until gap) put(last + t, lb + t)
                }
            }
            last = a
        }

        var changed = 0
        val cur = out.cursor()
        val zc = official.texts.cursor()
        for (b in 0 until N) {
            val a = target[b]
            if (a < 0) continue
            val j = oOrd[a]
            val k = nOrd[b]
            val lo = oc.load(j)
            if (!hasHangul(oc.bytes, lo)) continue
            val lc = cur.load(k)
            if (Texts.equal(oc.bytes, lo, cur.bytes, lc)) continue
            val lz = zc.load(k)
            if (shape(oc.bytes, lo) != shape(zc.bytes, lz)) continue
            out.set(k, OLD_PATCH, oldPatch.texts, j)
            changed++
        }
        return changed
    }

    /**
     * 한패가 같은 게임 버전 공식 원문과 같은 자리(Id)에 번역을 넣었는지.
     * 한패에서 번역하지 않은 줄(한글 없이 한자만)은 같은 Id 의 원문과 똑같아야 한다.
     * 실측: 정상 한패 100%, 업데이트 직후 올라온 깨진 한패 0%(09-22)·78%(08-11).
     */
    data class Alignment(val untranslated: Int, val matching: Int) {
        val ratio: Double get() = if (untranslated == 0) 1.0 else matching.toDouble() / untranslated

        /** 판단할 만큼 줄이 있고 [MIN_ALIGNMENT] 이상 맞음. 줄이 너무 적으면 판단 못 함 (null) */
        val ok: Boolean? get() = if (untranslated < MIN_UNTRANSLATED) null else ratio >= MIN_ALIGNMENT
    }

    const val MIN_ALIGNMENT = 0.90
    private const val MIN_UNTRANSLATED = 20

    fun alignment(official: LangTable, patch: LangTable): Alignment {
        require(official.sameIds(patch)) { "공식 원문과 한패의 게임 버전이 다릅니다" }
        val oOrd = official.sortedOrder()
        val pOrd = patch.sortedOrder()
        val zc = official.texts.cursor()
        val kc = patch.texts.cursor()
        var untranslated = 0
        var matching = 0
        for (k in oOrd.indices) {
            val lk = kc.load(pOrd[k])
            if (hasHangul(kc.bytes, lk) || !hasHan(kc.bytes, lk)) continue
            untranslated++
            val lz = zc.load(oOrd[k])
            if (Texts.equal(kc.bytes, lk, zc.bytes, lz)) matching++
        }
        return Alignment(untranslated, matching)
    }

    private const val MIN_RUN = 3
    private const val MAX_RUN = 64
    private const val MAX_CANDIDATES = 16

    /** 빈 문장은 0 (어떤 문장과도 맞지 않는 것으로 친다) */
    private fun key(c: Texts.Cursor, i: Int): Long {
        val l = c.load(i)
        return if (l == 0) 0L else nonZero(TranslationMemory.hash(c.bytes, l))
    }

    private fun nonZero(h: Long) = if (h == 0L) 1L else h

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private val SHAPE = Regex("""\{[0-9]+\}|%[0-9.]*[sdf]|<[^<>]{1,40}>|\\n|\n|[0-9]+(?:\.[0-9]+)?""")

    /** 번역해도 그대로 남아야 하는 것: 태그, 자리표시자, 줄바꿈, 숫자 (순서 무관). utf8 의 앞 n 바이트 */
    internal fun shape(utf8: ByteArray, n: Int = utf8.size): String =
        SHAPE.findAll(decodeUtf8(utf8, n)).map { it.value }.sorted().joinToString("\u0001")

    /**
     * UTF-8 → UTF-16. 깨진 바이트는 한 바이트마다 U+FFFD.
     * 플랫폼 디코더(JVM·.NET)는 깨진 바이트를 서로 다르게 바꾸므로 Kotlin·C#·C++ 가 같은 규칙을 직접 쓴다.
     */
    internal fun decodeUtf8(b: ByteArray, n: Int = b.size): String {
        val sb = StringBuilder(n)
        fun cont(k: Int) = k < n && (b[k].toInt() and 0xC0) == 0x80
        fun at(k: Int) = b[k].toInt() and 0x3F
        var i = 0
        while (i < n) {
            val c = b[i].toInt() and 0xff
            if (c < 0x80) { sb.append(c.toChar()); i++; continue }
            if (c in 0xC2..0xDF && cont(i + 1)) { sb.append((((c and 0x1F) shl 6) or at(i + 1)).toChar()); i += 2; continue }
            if (c in 0xE0..0xEF && cont(i + 1) && cont(i + 2)) {
                sb.append((((c and 0x0F) shl 12) or (at(i + 1) shl 6) or at(i + 2)).toChar()); i += 3; continue
            }
            if (c in 0xF0..0xF4 && cont(i + 1) && cont(i + 2) && cont(i + 3)) {
                val cp = ((c and 0x07) shl 18) or (at(i + 1) shl 12) or (at(i + 2) shl 6) or at(i + 3)
                if (cp <= 0x10FFFF) { sb.appendCodePoint(cp); i += 4; continue }
            }
            sb.append('�'); i++
        }
        return sb.toString()
    }

    /** UTF-8(앞 n 바이트) 안에 CJK 통합 한자(U+4E00..U+9FFF)가 있는지 */
    fun hasHan(utf8: ByteArray, n: Int = utf8.size): Boolean {
        var i = 0
        while (i + 2 < n) {
            val b0 = utf8[i].toInt() and 0xff
            if (b0 in 0xE4..0xE9) {
                val cp = ((b0 and 0x0F) shl 12) or ((utf8[i + 1].toInt() and 0x3F) shl 6) or (utf8[i + 2].toInt() and 0x3F)
                if (cp in 0x4E00..0x9FFF) return true
                i += 3
            } else i++
        }
        return false
    }

    /** UTF-8(앞 n 바이트) 안에 한글 음절(U+AC00..U+D7A3)이 있는지 */
    fun hasHangul(utf8: ByteArray, n: Int = utf8.size): Boolean {
        var i = 0
        while (i + 2 < n) {
            val b0 = utf8[i].toInt() and 0xff
            if (b0 in 0xEA..0xED) {
                val cp = ((b0 and 0x0F) shl 12) or ((utf8[i + 1].toInt() and 0x3F) shl 6) or (utf8[i + 2].toInt() and 0x3F)
                if (cp in 0xAC00..0xD7A3) return true
                i += 3
            } else i++
        }
        return false
    }
}
