package com.hoho.snqxkr.langtable

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 중국어 원문 → 한국어 번역.
 *
 * 게임 업데이트마다 Id 가 전부 다시 매겨지므로 Id 가 아니라 원문으로 찾는다.
 * 원문은 64비트 해시로만 들고 있어 25만 줄이 30MB 안팎이다 (충돌 확률 약 10^-9).
 * 한패는 같은 원문을 항상 같은 번역으로 옮기므로(08월 버전 실측: 갈리는 원문 0개) 원문 하나에 번역 하나면 된다.
 */
class TranslationMemory private constructor(
    private val keys: LongArray,       // 오름차순
    private val values: Array<ByteArray>,
) {
    val size: Int get() = keys.size

    operator fun get(source: ByteArray): ByteArray? {
        val i = keys.binarySearch(hash(source))
        return if (i >= 0) values[i] else null
    }

    /** 두 메모리를 합친다. 같은 원문이면 newer 의 번역이 이긴다. */
    fun mergedWith(newer: TranslationMemory): TranslationMemory {
        val map = HashMap<Long, ByteArray>(size + newer.size)
        for (i in keys.indices) map[keys[i]] = values[i]
        for (i in newer.keys.indices) map[newer.keys[i]] = newer.values[i]
        return fromMap(map)
    }

    fun writeTo(file: File) {
        DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(size)
            for (i in keys.indices) {
                out.writeLong(keys[i])
                out.writeInt(values[i].size)
                out.write(values[i])
            }
        }
    }

    companion object {
        private const val MAGIC = 0x534e514d // "SNQM"

        /**
         * 같은 게임 버전의 공식 원문과 한패를 같은 Id 끼리 짝지어 만든다.
         * 한패가 원문을 그대로 둔 문장(미번역, 숫자·기호)은 넣지 않는다.
         */
        fun build(official: LangTable, patch: LangTable): TranslationMemory {
            require(official.layoutKey() == patch.layoutKey()) { "공식 원문과 한패의 게임 버전이 다릅니다" }
            val patchOrder = patch.sortedOrder()
            val patchIds = LongArray(patch.size) { patch.ids[patchOrder[it]] }
            val map = HashMap<Long, ByteArray>(official.size)
            for (i in 0 until official.size) {
                val j = patchIds.binarySearch(official.ids[i])
                val ko = patch.texts[patchOrder[j]]
                val zh = official.texts[i]
                if (!ko.contentEquals(zh)) map.putIfAbsent(hash(zh), ko)
            }
            return fromMap(map)
        }

        fun read(file: File): TranslationMemory =
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { input ->
                require(input.readInt() == MAGIC) { "번역 메모리 파일이 아닙니다" }
                val n = input.readInt()
                val keys = LongArray(n)
                val values = arrayOfNulls<ByteArray>(n)
                for (i in 0 until n) {
                    keys[i] = input.readLong()
                    values[i] = ByteArray(input.readInt()).also { input.readFully(it) }
                }
                @Suppress("UNCHECKED_CAST")
                TranslationMemory(keys, values as Array<ByteArray>)
            }

        private fun fromMap(map: Map<Long, ByteArray>): TranslationMemory {
            val keys = map.keys.toLongArray().also { it.sort() }
            return TranslationMemory(keys, Array(keys.size) { map.getValue(keys[it]) })
        }

        /** FNV-1a 64 + murmur3 fmix64 */
        fun hash(bytes: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL
            for (x in bytes) {
                h = h xor (x.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33); h *= -0xae502812aa7333L
            h = h xor (h ushr 33); h *= -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }
    }
}
