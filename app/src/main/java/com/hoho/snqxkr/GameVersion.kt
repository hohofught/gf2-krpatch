package com.hoho.snqxkr

/**
 * 지금 게임 버전(Id 집합 지문)을 무엇으로 정할지. 윈도우 `GameVersion.cs` 와 같은 규칙이다 (GameVersionTest 와 같은 표).
 *
 * 게임 폴더의 한패 파일은 사용자가 바꿀 수 있다 (다른 판 한패를 넣거나, 직접 고치다 깨뜨리거나).
 * 그래서 그 파일의 지문은 게임이 쓴 공식 원본이거나 이 앱이 버전을 확인하고 넣은 파일일 때만 게임 버전으로 믿는다.
 * 그 밖의 파일이면 마지막으로 믿은 지문을 쓰되, 그 사이 게임이 표 폴더를 다시 썼으면(도장이 바뀜) 모른다("")고 한다.
 * 게임 업데이트는 표 수천 개를 한꺼번에 다시 쓰므로, 한패 파일을 뺀 표들의 도장이 그대로면 게임 버전도 그대로다.
 * 모를 때는 적용을 막지 않는다 (예전처럼 엉뚱한 파일을 근거로 막으면 고칠 길이 없어진다).
 */
object GameVersion {
    /** 믿은 지문과 그때 표 폴더 도장 */
    data class Record(val layout: String, val stamp: String)

    /** layout: 지금 게임 버전 (모르면 ""), remember: 새로 기억할 기록 (null 이면 그대로 둔다) */
    data class Decision(val layout: String, val remember: Record?)

    /**
     * @param official 게임 폴더 파일이 공식 원본(한글 없음)이고 표로 읽힌다
     * @param trusted 이 앱이 넣은 파일(해시 일치)이고 넣을 때 게임 버전을 알았다
     * @param fileLayout 게임 폴더 파일의 지문 (표로 못 읽으면 "")
     * @param stamp 지금 표 폴더 도장 (모르면 "")
     * @param record 마지막으로 믿은 기록
     */
    fun decide(official: Boolean, trusted: Boolean, fileLayout: String, stamp: String, record: Record): Decision = when {
        official && fileLayout.isNotEmpty() -> Decision(fileLayout, Record(fileLayout, stamp))
        stamp.isNotEmpty() && record.stamp == stamp -> Decision(record.layout, null)
        trusted && fileLayout.isNotEmpty() -> Decision(fileLayout, Record(fileLayout, stamp))
        else -> Decision("", null)
    }
}
