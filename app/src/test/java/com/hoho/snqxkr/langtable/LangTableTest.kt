package com.hoho.snqxkr.langtable

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 파일로 검증한다 (-PsnqxSamples=폴더). 저장소에는 게임 파일을 넣지 않는다.
 *  official-0824.bytes : 중섭 공식 원문 (08월 게임 버전)
 *  patch-0827.bytes    : 같은 버전용 커뮤니티 한패
 *  patch-current.bytes : 최신 한패
 */
class LangTableTest {

    private fun sample(name: String): File {
        val dir = System.getProperty("snqx.samples").orEmpty()
        val f = File(dir, name)
        assumeTrue("샘플 없음: $f", dir.isNotEmpty() && f.isFile)
        return f
    }

    @Test
    fun `한패 파싱 후 재작성하면 바이트 단위로 같다`() {
        val raw = sample("patch-current.bytes").readBytes()
        assertArrayEquals(raw, LangTable.parse(raw).toBytes())
    }

    @Test
    fun `공식 원문과 같은 버전 한패는 지문이 같다`() {
        val official = LangTable.read(sample("official-0824.bytes"))
        val patch = LangTable.read(sample("patch-0827.bytes"))
        assertEquals(official.layoutKey(), patch.layoutKey())
        val current = LangTable.read(sample("patch-current.bytes"))
        assert(official.layoutKey() != current.layoutKey())
    }

    /**
     * 같은 버전 원문을 번역 메모리로 복구하면 커뮤니티 한패와 같아야 한다.
     * 한패가 같은 원문을 어떤 곳은 번역하고 어떤 곳은 중국어로 둔 경우(08월 버전 7곳)만
     * 복구본이 번역으로 채운다. 한패의 번역과 다른 번역이 나오면 실패다.
     */
    @Test
    fun `같은 버전 원문을 복구하면 한패와 같다`() {
        val official = LangTable.read(sample("official-0824.bytes"))
        val patch = LangTable.read(sample("patch-0827.bytes"))
        val tm = TranslationMemory.build(official, patch)
        val result = PatchRepair.repair(official, tm)
        println("TM ${tm.size} 줄, 복구 ${result.translated} / 중국어로 남음 ${result.leftChinese}")

        val byId = HashMap<Long, ByteArray>(patch.size).apply { for (i in 0 until patch.size) put(patch.ids[i], patch.texts[i]) }
        val diffs = (0 until result.table.size).filter { !result.table.texts[it].contentEquals(byId[result.table.ids[it]]) }
        val conflicting = diffs.filter { !byId.getValue(result.table.ids[it]).contentEquals(official.texts[it]) }
        conflicting.take(5).forEach { i ->
            println("  id ${result.table.ids[i]}: 복구 ${String(result.table.texts[i])} / 한패 ${String(byId.getValue(result.table.ids[i]))}")
        }
        assertEquals("한패와 다르게 번역한 문장", 0, conflicting.size)
        println("한패가 중국어로 둔 곳을 번역으로 채운 문장 ${diffs.size}개")
    }

    @Test
    fun `파일로 흘려 쓴 결과도 한패와 바이트 단위로 같다`() {
        val src = sample("patch-current.bytes")
        val out = File.createTempFile("snqx-table", ".bytes").apply { deleteOnExit() }
        LangTable.read(src).writeTo(out)
        assertArrayEquals(src.readBytes(), out.readBytes())
    }

    @Test
    fun `Id 만 읽은 지문과 색인만 읽은 지문이 버전을 똑같이 가른다`() {
        val official = sample("official-0824.bytes")
        val same = sample("patch-0827.bytes")
        val other = sample("patch-current.bytes")
        assertEquals(LangTable.read(official).layoutKey(), LangTable.layoutKeyOf(LangTable.readIds(official)))
        assertEquals(LangTable.layoutKeyOf(LangTable.readIds(official)), LangTable.layoutKeyOf(LangTable.readIds(same)))
        assertEquals(LangTable.chunkKey(official), LangTable.chunkKey(same))
        assert(LangTable.chunkKey(official) != LangTable.chunkKey(other))
    }

    /** GitHub 이력의 게임 버전별 최신 한패 (V6 07-22 → V1 09-27). 있는 것만 검사한다. */
    private val versionPatches = listOf(
        "patch-v6-0722.bytes", "patch-0804.bytes", "patch-0827.bytes",
        "patch-v3-0901.bytes", "patch-v2-0919.bytes", "patch-current.bytes",
    )

    private fun samples(names: List<String>): List<File> {
        val dir = System.getProperty("snqx.samples").orEmpty()
        val files = names.map { File(dir, it) }.filter { it.isFile }
        assumeTrue("샘플 없음", files.isNotEmpty())
        return files
    }

    @Test
    fun `모든 버전 한패를 파싱 후 재작성하면 바이트 단위로 같다`() {
        for (f in samples(versionPatches)) {
            val raw = f.readBytes()
            assertArrayEquals(f.name, raw, LangTable.parse(raw).toBytes())
            println("  ${f.name}: 재작성 일치 (${LangTable.parse(raw).size}줄)")
        }
    }

    @Test
    fun `색인 지문과 Id 지문이 버전을 똑같이 나눈다`() {
        val files = samples(versionPatches + listOf("official-0824.bytes", "official-pc-current.bytes"))
        val byChunk = files.groupBy { LangTable.chunkKey(it) }.values.map { g -> g.map { it.name }.toSet() }.toSet()
        val byIds = files.groupBy { LangTable.layoutKeyOf(LangTable.readIds(it)) }.values.map { g -> g.map { it.name }.toSet() }.toSet()
        println("  버전 묶음: $byIds")
        assertEquals(byIds, byChunk)
    }

    @Test
    fun `현재 버전 원문을 현재 한패로 만든 메모리로 복구하면 한패와 같다`() {
        val official = LangTable.read(sample("official-pc-current.bytes"))
        val patch = LangTable.read(sample("patch-current.bytes"))
        val result = PatchRepair.repair(official, TranslationMemory.build(official, patch))
        val conflicting = compare(official, result.table, patch).conflicting
        println("  복구 ${result.translated} / 중국어로 남음 ${result.leftChinese}, 한패와 다른 번역 $conflicting")
        assertEquals(0, conflicting)
    }

    /**
     * 실전 시나리오: 08월(V4) 번역 메모리만 가진 상태에서 게임이 세 번 업데이트돼 지금(V1)이 됐다.
     * 새 한패가 나오기 전에 복구한 결과를, 실제로 나온 지금 한패와 비교한다.
     */
    @Test
    fun `08월 번역 메모리로 세 번 업데이트된 현재 원본을 복구`() {
        val tm = TranslationMemory.build(LangTable.read(sample("official-0824.bytes")), LangTable.read(sample("patch-0827.bytes")))
        val official = LangTable.read(sample("official-pc-current.bytes"))
        val truth = LangTable.read(sample("patch-current.bytes"))
        val result = PatchRepair.repair(official, tm)
        val c = compare(official, result.table, truth)
        println(
            "  번역 메모리 ${tm.size}줄 → 현재 원본 ${official.size}줄 복구\n" +
                "  한국어로 복구 ${result.translated}줄 (중국어 문장 중 ${PatchRepairPercent(result.coverage)}), 중국어로 남음 ${result.leftChinese}줄\n" +
                "  실제 현재 한패와 비교: 같은 번역 ${c.same}, 다른 번역 ${c.conflicting}, 한패는 번역했는데 복구는 중국어 ${c.missing}"
        )
        c.examples.forEach { println("    예) $it") }
        // 윈도우 C# 엔진(SnqxKR.EngineCheck)·네이티브 엔진과 같은 숫자여야 한다
        assertEquals(252684, tm.size)
        assertEquals(463540, result.translated)
        assertEquals(30599, result.leftChinese)
    }

    /**
     * 업데이트 직후 올라온 한패는 번역이 엉뚱한 자리에 들어 있었다 (GitHub 이력).
     * 샘플 폴더의 history/ 에 커밋별 한패가 있을 때만 돈다.
     */
    @Test
    fun `공식 원문과 자리가 맞지 않는 한패를 가려낸다`() {
        val v1 = LangTable.read(sample("official-pc-current.bytes"))
        val v4 = LangTable.read(sample("official-0824.bytes"))
        val cases = listOf(
            Triple(v1, "history/20260922-0725-0cce1eb.bytes", false), // 09-22: 전부 어긋남
            Triple(v1, "history/20260927-1419-76f18c5.bytes", true),
            Triple(v4, "history/20260811-1111-0398002.bytes", false), // 08-11: 일부 섞임
            Triple(v4, "history/20260827-0119-810ed14.bytes", true),
        )
        for ((official, name, expected) in cases) {
            val a = PatchRepair.alignment(official, LangTable.read(sample(name)))
            println("  $name: 번역 안 된 줄 ${a.untranslated} 중 원문과 같은 자리 ${a.matching} (${PatchRepairPercent(a.ratio)})")
            assertEquals(name, expected, a.ok)
        }
    }

    /**
     * 폰에서 흔한 경우: 번역 메모리는 08월(V4) 것인데 게임에는 09-19(V2) 한패를 쓰고 있었고, 게임이 V1 로 업데이트됐다.
     * 2단계로 V2 한패의 번역을 V1 자리로 옮기면 실제 V1 한패와 같은 줄이 늘어야 하고, 옮긴 줄은 대부분 V1 한패와 같아야 한다.
     */
    @Test
    fun `옛 한패 번역을 새 버전 자리로 옮긴다`() {
        val tm = TranslationMemory.build(LangTable.read(sample("official-0824.bytes")), LangTable.read(sample("patch-0827.bytes")))
        val official = LangTable.read(sample("official-pc-current.bytes"))
        val oldPatch = LangTable.read(sample("patch-v2-0919.bytes"))
        val truth = LangTable.read(sample("patch-current.bytes"))
        val only = PatchRepair.repair(official, tm)
        val both = PatchRepair.repair(official, tm, oldPatch)
        val a = compare(official, only.table, truth)
        val b = compare(official, both.table, truth)
        // 옮긴 줄만 따로: 실제 V1 한패와 같은 비율
        var moved = 0; var movedSame = 0
        val byId = HashMap<Long, ByteArray>(truth.size).apply { for (i in 0 until truth.size) put(truth.ids[i], truth.texts[i]) }
        for (i in 0 until official.size) {
            if (both.table.texts[i].contentEquals(only.table.texts[i])) continue
            moved++
            if (both.table.texts[i].contentEquals(byId[official.ids[i]])) movedSame++
        }
        println(
            "  메모리만: 한국어 ${only.translated}, V1 한패와 같음 ${a.same}\n" +
                "  옛 한패까지: 한국어 ${both.translated} (옮김 ${both.fromPatch}), V1 한패와 같음 ${b.same}\n" +
                "  옮긴 줄 $moved 중 V1 한패와 같음 $movedSame (${PatchRepairPercent(movedSame.toDouble() / moved.coerceAtLeast(1))})"
        )
        assertEquals(moved, both.fromPatch)
        assert(b.same > a.same + 4000) { "옛 한패로 늘어난 줄이 너무 적음: ${a.same} → ${b.same}" }
        assert(movedSame >= moved * 0.95) { "옮긴 줄의 정확도가 낮음: $movedSame / $moved" }
    }

    @Test
    fun `번역 메모리는 새 세대가 이기고 최대 크기를 넘으면 오래된 세대부터 뺀다`() {
        fun table(vararg rows: Pair<String, String>) =
            LangTable(LongArray(rows.size) { it + 1L }, Array(rows.size) { rows[it].first.toByteArray() }) to
                LangTable(LongArray(rows.size) { it + 1L }, Array(rows.size) { rows[it].second.toByteArray() })
        val (o1, p1) = table("甲" to "갑", "乙" to "을", "丙" to "병")
        val (o2, p2) = table("乙" to "을2", "丁" to "정")
        val old = TranslationMemory.build(o1, p1)
        val merged = old.mergedWith(TranslationMemory.build(o2, p2))
        assertEquals(4, merged.size)
        assertEquals("을2", String(merged["乙".toByteArray()]!!))
        assertEquals("갑", String(merged["甲".toByteArray()]!!))

        // 한 줄 = 16바이트 + 번역 길이(3). 두 줄만 남게 자르면 새 세대(乙, 丁)가 남는다
        val capped = merged.capped(8L + 2 * (16 + 4))
        assertEquals(2, capped.size)
        assertEquals(null, capped["甲".toByteArray()])
        assertEquals("정", String(capped["丁".toByteArray()]!!))

        // 저장 후 읽어도 세대가 남아 다음 합치기에서 이어진다
        val f = File.createTempFile("snqx-tm", ".bin").apply { deleteOnExit() }
        merged.writeTo(f)
        val back = TranslationMemory.read(f)
        assertEquals(merged.byteSize, f.length())
        assertEquals(merged.byteSize, back.byteSize)
        val again = back.mergedWith(TranslationMemory.build(table("戊" to "무").first, table("戊" to "무").second)).capped(8L + 16 + 3)
        assertEquals("무", String(again["戊".toByteArray()]!!))
    }

    @Test
    fun `깨진 번역 메모리만 형식 오류로 알린다`() {
        val o = LangTable(longArrayOf(1, 2), arrayOf("甲".toByteArray(), "乙".toByteArray()))
        val p = LangTable(longArrayOf(1, 2), arrayOf("갑".toByteArray(), "을".toByteArray()))
        val good = File.createTempFile("snqx-tm", ".bin").apply { deleteOnExit() }
        TranslationMemory.build(o, p).writeTo(good)
        val bytes = good.readBytes()
        val oneRowHeader = byteArrayOf(0x53, 0x4e, 0x51, 0x32, 0, 0, 0, 1)
        // 빈 파일, 머리가 끊김, 줄 수가 파일보다 큼, 줄 머리에서 끊김, 번역 도중 끊김, 다른 파일
        val broken = listOf(
            ByteArray(0), bytes.copyOf(6), bytes.copyOf(8), oneRowHeader + ByteArray(12),
            bytes.copyOf(bytes.size - 1), "not a translation memory".toByteArray(),
        )
        for (b in broken) {
            val f = File.createTempFile("snqx-tm", ".bin").apply { deleteOnExit(); writeBytes(b) }
            assertThrows(CorruptMemoryException::class.java) { TranslationMemory.read(f) }
        }
        // 못 여는 것은 형식 오류가 아니다 (합칠 때 쌓아 둔 메모리를 새것으로 덮으면 안 된다. C++·C# 과 같음)
        val e = assertThrows(java.io.IOException::class.java) { TranslationMemory.read(File(good.path + ".none")) }
        assertFalse(e is CorruptMemoryException)
    }

    private data class Comparison(val same: Int, val conflicting: Int, val missing: Int, val examples: List<String>)

    /** 복구본과 실제 한패를 같은 Id 끼리 비교 */
    private fun compare(official: LangTable, repaired: LangTable, truth: LangTable): Comparison {
        val byId = HashMap<Long, ByteArray>(truth.size).apply { for (i in 0 until truth.size) put(truth.ids[i], truth.texts[i]) }
        var same = 0; var conflicting = 0; var missing = 0
        val examples = ArrayList<String>()
        for (i in 0 until repaired.size) {
            val zh = official.texts[i]; val got = repaired.texts[i]; val want = byId[repaired.ids[i]] ?: continue
            if (want.contentEquals(zh)) continue                    // 한패도 원문 그대로 둔 문장
            when {
                got.contentEquals(want) -> same++
                got.contentEquals(zh) -> missing++                   // 새 문장: 복구는 중국어로 둠
                else -> {
                    conflicting++                                    // 한패가 번역을 고쳤거나 문맥이 다름
                    if (examples.size < 5) examples += "${String(zh)} → 복구 '${String(got)}' / 한패 '${String(want)}'"
                }
            }
        }
        return Comparison(same, conflicting, missing, examples)
    }

    private fun PatchRepairPercent(r: Double) = String.format("%.1f%%", r * 100)

    @Test
    fun `번역 메모리 저장 후 읽어도 같다`() {
        val official = LangTable.read(sample("official-0824.bytes"))
        val tm = TranslationMemory.build(official, LangTable.read(sample("patch-0827.bytes")))
        val f = File.createTempFile("snqx-tm", ".bin").apply { deleteOnExit() }
        tm.writeTo(f)
        val back = TranslationMemory.read(f)
        assertEquals(tm.size, back.size)
        val probe = official.texts[(0 until official.size).first { tm[official.texts[it]] != null }]
        assertArrayEquals(tm[probe], back[probe])
    }
}
