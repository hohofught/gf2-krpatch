package com.hoho.snqxkr

/**
 * 큰 버튼(주 버튼) 하나로 지금 할 가장 좋은 일. 윈도우 `PrimaryAction.cs` 와 같은 규칙이다 (PrimaryActionTest 와 같은 표).
 *
 *  - 임시 복구 중이고 받아 둔 한패가 게임 버전에 맞으면: 정식 한패로 교체
 *  - 받아 둔 한패가 옛 버전용인데 서버에 새 한패가 있으면: 새 한패를 받아, 이 버전용이면 넣고(교체) 아직 아니면 임시 복구
 *  - 새 한패가 없고 임시 복구를 할 수 있으면: 임시 복구
 *  - 할 수 있는 게 없으면(임시 복구본으로 기다리는 중, 번역 메모리 없음): 새 한패 확인
 *  - 그 밖: 한글패치 적용 (받기 + 적용)
 */
enum class PrimaryAction(val label: String) {
    APPLY("한글패치 적용"),
    REPLACE("정식 한패로 교체"),
    UPDATE_APPLY("새 한패 받아서 적용"),
    UPDATE_REPLACE("새 한패 받아서 교체"),
    REPAIR("임시 복구"),
    CHECK("새 한패 확인");

    companion object {
        /**
         * @param gameReady 게임 데이터(Table 폴더)가 있는지. false 면 버튼 없음
         * @param repaired 게임 폴더에 임시 복구본이 들어 있는지
         * @param matches 받아 둔 한패가 게임 버전에 맞는지 (모르면 null)
         * @param canRepair 임시 복구를 할 수 있는지 (이 버전 공식 원본 + 번역 메모리)
         * @param remotePending 서버에 아직 받지 않은 새 한패가 있는지
         */
        fun decide(gameReady: Boolean, repaired: Boolean, matches: Boolean?, canRepair: Boolean, remotePending: Boolean): PrimaryAction? {
            if (!gameReady) return null
            val mismatch = matches == false
            return when {
                repaired && !mismatch -> REPLACE
                mismatch && remotePending -> if (repaired) UPDATE_REPLACE else UPDATE_APPLY
                mismatch && !repaired && canRepair -> REPAIR
                mismatch -> CHECK
                else -> APPLY
            }
        }
    }
}
