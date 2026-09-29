package com.hoho.snqxkr.langtable

/**
 * 게임 업데이트로 한패가 맞지 않게 됐을 때, 새 공식 원문을 번역 메모리로 다시 한국어로 만든다.
 * 메모리에 없는 문장(새 콘텐츠)은 중국어 원문 그대로 둔다. 색인은 LangTable.toBytes() 가 다시 계산한다.
 */
object PatchRepair {

    data class Result(
        val table: LangTable,
        val total: Int,         // 전체 문장
        val translated: Int,    // 번역 메모리로 한국어가 된 문장
        val leftChinese: Int,   // 한자가 있는데 메모리에 없어 중국어로 남은 문장
    ) {
        val coverage: Double get() = translated.toDouble() / (translated + leftChinese).coerceAtLeast(1)
    }

    fun repair(official: LangTable, tm: TranslationMemory): Result {
        var translated = 0
        var leftChinese = 0
        val texts = Array(official.size) { i ->
            val zh = official.texts[i]
            val ko = tm[zh]
            when {
                ko != null -> { translated++; ko }
                hasHan(zh) -> { leftChinese++; zh }
                else -> zh
            }
        }
        val table = LangTable(official.ids, texts, official.chunkSize, official.headerFlag)
        return Result(table, official.size, translated, leftChinese)
    }

    /** UTF-8 안에 CJK 통합 한자(U+4E00..U+9FFF)가 있는지 */
    fun hasHan(utf8: ByteArray): Boolean {
        var i = 0
        while (i + 2 < utf8.size) {
            val b0 = utf8[i].toInt() and 0xff
            if (b0 in 0xE4..0xE9) {
                val cp = ((b0 and 0x0F) shl 12) or ((utf8[i + 1].toInt() and 0x3F) shl 6) or (utf8[i + 2].toInt() and 0x3F)
                if (cp in 0x4E00..0x9FFF) return true
                i += 3
            } else i++
        }
        return false
    }
}
