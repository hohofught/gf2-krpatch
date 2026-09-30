namespace SnqxKR
{
    /// <summary>
    /// 큰 버튼(주 버튼) 하나로 지금 할 가장 좋은 일. 안드로이드 <c>PrimaryAction.kt</c> 와 같은 규칙이다 (PrimaryActionTest 와 같은 표).
    ///  - 임시 복구 중이고 받아 둔 한패가 게임 버전에 맞으면: 정식 한패로 교체
    ///  - 받아 둔 한패가 옛 버전용인데 서버에 새 한패가 있으면: 새 한패를 받아, 이 버전용이면 넣고(교체) 아직 아니면 임시 복구
    ///  - 새 한패가 없고 임시 복구를 할 수 있으면: 임시 복구
    ///  - 할 수 있는 게 없으면(임시 복구본으로 기다리는 중, 번역 메모리 없음): 새 한패 확인
    ///  - 그 밖: 한글패치 적용 (받기 + 적용)
    /// </summary>
    public enum PrimaryAction { Apply, Replace, UpdateApply, UpdateReplace, Repair, Check }

    public static class PrimaryActions
    {
        /// <param name="gameReady">게임 데이터(Table 폴더)가 있는지. false 면 버튼 없음</param>
        /// <param name="repaired">게임 폴더에 임시 복구본이 들어 있는지</param>
        /// <param name="matches">받아 둔 한패가 게임 버전에 맞는지 (모르면 null)</param>
        /// <param name="canRepair">임시 복구를 할 수 있는지 (이 버전 공식 원본 + 번역 메모리)</param>
        /// <param name="remotePending">서버에 아직 받지 않은 새 한패가 있는지</param>
        public static PrimaryAction? Decide(bool gameReady, bool repaired, bool? matches, bool canRepair, bool remotePending)
        {
            if (!gameReady) return null;
            bool mismatch = matches == false;
            if (repaired && !mismatch) return PrimaryAction.Replace;
            if (mismatch && remotePending) return repaired ? PrimaryAction.UpdateReplace : PrimaryAction.UpdateApply;
            if (mismatch && !repaired && canRepair) return PrimaryAction.Repair;
            if (mismatch) return PrimaryAction.Check;
            return PrimaryAction.Apply;
        }

        public static string Label(PrimaryAction action) => action switch
        {
            PrimaryAction.Replace => "정식 한패로 교체",
            PrimaryAction.UpdateApply => "새 한패 받아서 적용",
            PrimaryAction.UpdateReplace => "새 한패 받아서 교체",
            PrimaryAction.Repair => "임시 복구",
            PrimaryAction.Check => "새 한패 확인",
            _ => "한글패치 적용",
        };
    }
}
