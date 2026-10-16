package com.hoho.snqxkr

import com.hoho.snqxkr.GameVersion.Decision
import com.hoho.snqxkr.GameVersion.Record
import org.junit.Assert.assertEquals
import org.junit.Test

/** 게임 버전 규칙. 윈도우 GameVersion.cs 도 같은 표를 따라야 한다 */
class GameVersionTest {
    private val none = Record("", "")
    private val a1 = Record("A", "s1")

    private fun d(official: Boolean = false, trusted: Boolean = false, file: String = "B", stamp: String = "s1", record: Record = a1) =
        GameVersion.decide(official, trusted, file, stamp, record)

    @Test
    fun `공식 원본이면 그 파일을 믿고 기억한다`() {
        assertEquals(Decision("A", a1), d(official = true, file = "A", record = none))
        // 게임이 한패 표만 다시 쓴 경우도 있을 수 있어 기록보다 공식 원본을 믿는다
        assertEquals(Decision("B", Record("B", "s1")), d(official = true, file = "B"))
    }

    @Test
    fun `도장이 그대로면 게임 폴더에 무엇이 들었든 기록대로`() {
        // 앱이 넣은 한패를 사용자가 다른 판으로 바꿔 넣은 경우 (이 경우가 고칠 길 없이 막혔다)
        assertEquals(Decision("A", null), d(file = "B"))
        // 표로 읽을 수 없게 깨진 파일
        assertEquals(Decision("A", null), d(file = ""))
        assertEquals(Decision("A", null), d(official = true, file = ""))
    }

    @Test
    fun `도장이 바뀌면 다른 파일로는 게임 버전을 모른다`() {
        assertEquals(Decision("", null), d(file = "B", stamp = "s2"))
        // 버전을 모른 채 넣은 파일도 근거가 되지 않는다
        assertEquals(Decision("", null), d(file = "A", stamp = "s2"))
    }

    @Test
    fun `앱이 버전을 확인하고 넣은 파일은 믿는다`() {
        assertEquals(Decision("A", Record("A", "s2")), d(trusted = true, file = "A", stamp = "s2"))
        // 이전 판에서 올라와 기록이 없을 때도
        assertEquals(Decision("A", a1), d(trusted = true, file = "A", record = none))
    }

    @Test
    fun `기록이 없으면 다른 파일로는 모른다`() = assertEquals(Decision("", null), d(file = "B", record = none))

    @Test
    fun `도장을 모를 때`() {
        assertEquals(Decision("A", Record("A", "")), d(official = true, file = "A", stamp = ""))
        assertEquals(Decision("A", Record("A", "")), d(trusted = true, file = "A", stamp = ""))
        assertEquals(Decision("", null), d(file = "B", stamp = ""))
    }
}
