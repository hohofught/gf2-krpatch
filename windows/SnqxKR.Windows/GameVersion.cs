namespace SnqxKR
{
    /// <summary>
    /// 지금 게임 버전(Id 집합 지문)을 무엇으로 정할지. 안드로이드 <c>GameVersion.kt</c> 와 같은 규칙이다 (GameVersionTest 와 같은 표).
    ///
    /// 게임 폴더의 한패 파일은 사용자가 바꿀 수 있다 (다른 판 한패를 넣거나, 직접 고치다 깨뜨리거나).
    /// 그래서 그 파일의 지문은 게임이 쓴 공식 원본이거나 이 앱이 버전을 확인하고 넣은 파일일 때만 게임 버전으로 믿는다.
    /// 그 밖의 파일이면 마지막으로 믿은 지문을 쓰되, 그 사이 게임이 표 폴더를 다시 썼으면(도장이 바뀜) 모른다("")고 한다.
    /// 게임 업데이트는 표 수천 개를 한꺼번에 다시 쓰므로, 한패 파일을 뺀 표들의 도장이 그대로면 게임 버전도 그대로다.
    /// 모를 때는 적용을 막지 않는다 (예전처럼 엉뚱한 파일을 근거로 막으면 고칠 길이 없어진다).
    /// </summary>
    public static class GameVersion
    {
        /// <summary>믿은 지문과 그때 표 폴더 도장</summary>
        public sealed record Record(string Layout, string Stamp);

        /// <summary>Layout: 지금 게임 버전 (모르면 ""), Remember: 새로 기억할 기록 (null 이면 그대로 둔다)</summary>
        public sealed record Decision(string Layout, Record? Remember);

        /// <param name="official">게임 폴더 파일이 공식 원본(한글 없음)이고 표로 읽힌다</param>
        /// <param name="trusted">이 앱이 넣은 파일(해시 일치)이고 넣을 때 게임 버전을 알았다</param>
        /// <param name="fileLayout">게임 폴더 파일의 지문 (표로 못 읽으면 "")</param>
        /// <param name="stamp">지금 표 폴더 도장 (모르면 "")</param>
        /// <param name="record">마지막으로 믿은 기록</param>
        public static Decision Decide(bool official, bool trusted, string fileLayout, string stamp, Record record) =>
            official && fileLayout.Length > 0 ? new Decision(fileLayout, new Record(fileLayout, stamp))
            : stamp.Length > 0 && record.Stamp == stamp ? new Decision(record.Layout, null)
            : trusted && fileLayout.Length > 0 ? new Decision(fileLayout, new Record(fileLayout, stamp))
            : new Decision("", null);
    }
}
