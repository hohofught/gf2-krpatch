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
        assertEquals(Decision("A", a1), d(official = true, file = "A"))
        // 게임이 표를 다시 쓴 뒤의 원본은 새 버전이다
        assertEquals(Decision("B", Record("B", "s2")), d(official = true, file = "B", stamp = "s2"))
    }

    @Test
    fun `도장이 그대로인데 다른 버전의 원본이면 게임이 쓴 것이 아니다`() {
        // 새 게임에 사용자가 옛 원본(앱 백업 등)을 넣은 경우: 믿으면 옛 원본을 이 버전 보관본으로 덮고 임시 복구도 옛 버전으로 만든다
        assertEquals(Decision("A", null), d(official = true, file = "B"))
    }

    @Test
    fun `도장이 그대로면 게임 폴더에 무엇이 들었든 기록대로`() {
        // 앱이 넣은 한패를 사용자가 다른 판으로 바꿔 넣은 경우 (이 경우가 고칠 길 없이 막혔다)
        assertEquals(Decision("A", null), d(file = "B"))
        assertEquals(Decision("A", null), d(trusted = true, file = "B"))
        // 표로 읽을 수 없게 깨진 파일
        assertEquals(Decision("A", null), d(file = ""))
        assertEquals(Decision("A", null), d(official = true, file = ""))
    }

    @Test
    fun `도장이 바뀌면 다른 파일로는 게임 버전을 모른다`() {
        assertEquals(Decision("", null), d(file = "B", stamp = "s2"))
        // 버전을 모른 채 넣은 파일도 근거가 되지 않는다
        assertEquals(Decision("", null), d(file = "A", stamp = "s2"))
        // 앱이 버전을 확인하고 넣은 파일이라도: 게임이 덮은 뒤 사용자가 같은 옛 한패를 다시 넣었을 수 있다
        assertEquals(Decision("", null), d(trusted = true, file = "A", stamp = "s2"))
    }

    @Test
    fun `견줄 기록이 없으면 앱이 버전을 확인하고 넣은 파일은 믿는다`() {
        // 이전 판에서 올라와 기록이 없을 때
        assertEquals(Decision("A", a1), d(trusted = true, file = "A", record = none))
        // 도장 없이 남긴 기록 (안드로이드 옛 filesvc)
        assertEquals(Decision("A", a1), d(trusted = true, file = "A", record = Record("B", "")))
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
