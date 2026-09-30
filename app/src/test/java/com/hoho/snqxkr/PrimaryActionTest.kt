package com.hoho.snqxkr

import org.junit.Assert.assertEquals
import org.junit.Test

/** 큰 버튼 규칙. 윈도우 PrimaryAction.cs 도 같은 표를 따라야 한다 */
class PrimaryActionTest {
    private fun d(ready: Boolean = true, repaired: Boolean = false, matches: Boolean? = true, canRepair: Boolean = false, remote: Boolean = false) =
        PrimaryAction.decide(ready, repaired, matches, canRepair, remote)

    @Test
    fun `게임 데이터가 없으면 버튼 없음`() = assertEquals(null, d(ready = false))

    @Test
    fun `평소에는 한글패치 적용`() {
        assertEquals(PrimaryAction.APPLY, d())
        assertEquals(PrimaryAction.APPLY, d(matches = null))
        assertEquals(PrimaryAction.APPLY, d(remote = true))
    }

    @Test
    fun `게임 업데이트로 풀렸을 때`() {
        assertEquals(PrimaryAction.REPAIR, d(matches = false, canRepair = true))
        assertEquals(PrimaryAction.CHECK, d(matches = false, canRepair = false))
        // 서버에 새 한패가 있으면 임시 복구보다 먼저 받아 본다
        assertEquals(PrimaryAction.UPDATE_APPLY, d(matches = false, canRepair = true, remote = true))
        assertEquals(PrimaryAction.UPDATE_APPLY, d(matches = false, canRepair = false, remote = true))
    }

    @Test
    fun `임시 복구 중일 때`() {
        assertEquals(PrimaryAction.REPLACE, d(repaired = true, matches = true))
        assertEquals(PrimaryAction.REPLACE, d(repaired = true, matches = null))
        assertEquals(PrimaryAction.CHECK, d(repaired = true, matches = false, canRepair = true))
        assertEquals(PrimaryAction.UPDATE_REPLACE, d(repaired = true, matches = false, remote = true))
    }
}
