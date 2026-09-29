package com.hoho.snqxkr.langtable

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        assert(result.coverage > 0.85) { "복구율이 너무 낮음: ${result.coverage}" }
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
        val probe = official.texts.first { tm[it] != null }
        assertArrayEquals(tm[probe], back[probe])
    }
}
