#!/usr/bin/env bash
# 시작 검사: APK 를 깔아 실제로 켜 보고, 죽거나 멈추지 않는지 본다.
# CI 에뮬레이터와 실기기에서 같은 스크립트를 쓴다. 2.2 처럼 "켜자마자 죽음" 은 빌드·단위 테스트로는 안 보인다.
#
#   tools/smoke-android.sh [-s 시리얼] [--replace] [--keep] [--recreate] [--out 폴더] app.apk [app2.apk ...]
#
#   -s 시리얼    adb 기기 (여럿 연결돼 있으면 필수)
#   --replace    이미 깔린 같은 앱 위에 덮어 깐다 (데이터 유지, 서명이 같아야 함). 없으면 깔린 앱이 있을 때 멈춘다
#   --keep       검사가 끝나도 지우지 않는다 (이 스크립트가 새로 깐 경우만 지운다. 원래 있던 앱은 절대 지우지 않는다)
#   --recreate   화면 회전으로 Activity 를 다시 만든다 (기기 설정을 잠깐 바꾸고 되돌린다. CI 에뮬레이터용)
#   --out 폴더   실패하면 logcat·화면을 여기에 남긴다 (기본 smoke-out)
#
# 보는 것: 첫 실행(데이터 없음) → [다시 만들기] → 홈 갔다 오기 → 강제 종료 후 다시 실행(저장된 설정으로 시작)
#          단계마다 events 버퍼의 am_crash·am_anr, 프로세스가 살아 있는지
# 못 보는 것: Shizuku 가 필요한 적용·복구 흐름 (docs/release-checklist.md 의 실기기 단계)
#
# arm64 전용 APK 를 x86_64 에뮬레이터에 못 깔면 그 APK 는 "건너뜀" 으로 끝난다 (실패 아님. 요약에 남는다).
set -u
export MSYS_NO_PATHCONV=1 # Git Bash: adb shell 인자의 /sdcard 같은 경로를 윈도우 경로로 바꾸지 않게

ADB="${ADB:-adb}"
SERIAL=""
REPLACE=0
KEEP=0
RECREATE=0
OUT="smoke-out"
APKS=()
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2 ;;
        --replace) REPLACE=1; shift ;;
        --keep) KEEP=1; shift ;;
        --recreate) RECREATE=1; shift ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) APKS+=("$1"); shift ;;
    esac
done
[ ${#APKS[@]} -gt 0 ] || { echo "APK 를 하나 이상 주세요 (-h 도움말)"; exit 2; }

adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
sh_() { adb_ shell "$@" | tr -d '\r'; }
note() { echo "  $*"; }
gh_() { [ "${GITHUB_ACTIONS:-}" = "true" ] && echo "::$1::$2"; return 0; }

# 로컬 경로를 adb 가 읽는 형태로 (Git Bash 의 /c/... 는 adb.exe 가 모른다)
host_path() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

# APK 의 패키지 이름. aapt2 가 없으면 배포판 이름으로 본다
package_of() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}" aapt
    aapt=$(ls -d "$sdk"/build-tools/*/aapt2* 2> /dev/null | sort -V | tail -1)
    if [ -n "$aapt" ]; then "$aapt" dump packagename "$(host_path "$1")" 2> /dev/null | tr -d '\r' && return; fi
    echo "com.hoho.snqxkr"
}

adb_ wait-for-device
DEVICE="$(sh_ getprop ro.product.model) / Android $(sh_ getprop ro.build.version.release) / $(sh_ getprop ro.product.cpu.abilist)"
echo "기기: $DEVICE"
mkdir -p "$OUT"

# 이 검사가 시작한 뒤의 로그만 본다 (기기 로그를 지우지 않는다)
SINCE=""
# epoch 초로 준다 (월-일 시각으로 주면 셸과 logcat 의 시간대가 다른 기기에서 구간이 어긋난다)
mark() { SINCE="$(sh_ date +%s).000"; }

# 지금까지 이 앱이 죽거나 멈췄는가. 문제면 1
problems() {
    local pkg="$1" ev
    ev=$(sh_ logcat -d -b events -v brief -T "$SINCE" 2> /dev/null | grep -E "am_(crash|anr)" | grep -F "$pkg")
    if [ -n "$ev" ]; then
        echo "$ev" | sed 's/^/    /'
        sh_ logcat -d -b crash -v brief -T "$SINCE" 2> /dev/null | sed -n "/Process: $pkg/,+30p" | head -40 | sed 's/^/    /'
        return 1
    fi
    return 0
}

alive() { [ -n "$(sh_ pidof "$1")" ]; }

save_evidence() {
    local tag="$1"
    sh_ logcat -d -b all -v threadtime -T "$SINCE" >"$OUT/$tag-logcat.txt" 2> /dev/null
    adb_ exec-out screencap -p > "$OUT/$tag-screen.png" 2> /dev/null
    note "증거: $OUT/$tag-logcat.txt, $OUT/$tag-screen.png"
}

# 단계 하나: 기다린 뒤 죽음·멈춤·프로세스를 본다
check_step() {
    local pkg="$1" step="$2" wait="$3" expect_alive="${4:-1}"
    sleep "$wait"
    if ! problems "$pkg"; then note "실패: $step 에서 죽거나 멈춤"; return 1; fi
    if [ "$expect_alive" = 1 ] && ! alive "$pkg"; then note "실패: $step 뒤 프로세스가 없음"; return 1; fi
    note "통과: $step"
}

launch() { sh_ am start -W -n "$2" | grep -q "Status: ok" || note "am start 상태가 ok 가 아님 ($1)"; }

ROT_SAVED=""
rotate_restore() {
    [ -n "$ROT_SAVED" ] || return 0
    sh_ settings put system user_rotation "${ROT_SAVED#* }" > /dev/null
    sh_ settings put system accelerometer_rotation "${ROT_SAVED% *}" > /dev/null
    ROT_SAVED=""
}

smoke_one() {
    local apk="$1" pkg comp installed_before=0 out tag rc=0
    pkg=$(package_of "$apk")
    tag=$(basename "$apk" .apk)
    echo "== $tag ($pkg)"

    # 보안 폴더 같은 다른 사용자에 깔린 것까지 본다 (APK 는 사용자끼리 같이 쓰므로 덮으면 거기도 바뀐다)
    if [ "$(sh_ "dumpsys package $pkg | grep -c 'Package \[$pkg\]'")" != 0 ]; then
        installed_before=1
        if [ $REPLACE = 0 ]; then
            note "멈춤: $pkg 가 이미 깔려 있다. 그 앱의 데이터를 지키려고 덮어쓰지 않는다 (덮어 깔려면 --replace)"
            return 1
        fi
    fi

    out=$(adb_ install -r --user current "$(host_path "$apk")" 2>&1 | tr -d '\r')
    if ! echo "$out" | grep -q "^Success"; then
        if echo "$out" | grep -q "NO_MATCHING_ABIS"; then
            note "건너뜀: 이 기기 CPU($(sh_ getprop ro.product.cpu.abi))로는 깔 수 없는 APK"
            gh_ warning "$tag 는 이 에뮬레이터에 깔 수 없어 시작 검사를 건너뜀 (NO_MATCHING_ABIS)"
            return 3
        fi
        note "실패: 설치 안 됨 · $(echo "$out" | tail -1)"
        return 1
    fi
    comp=$(sh_ cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tail -1)
    note "설치됨 (실행 화면 $comp)"

    mark
    sh_ am force-stop "$pkg"
    launch "첫 실행" "$comp"
    check_step "$pkg" "첫 실행" 10 || rc=1

    if [ $rc = 0 ] && [ $RECREATE = 1 ]; then
        ROT_SAVED="$(sh_ settings get system accelerometer_rotation) $(sh_ settings get system user_rotation)"
        sh_ settings put system accelerometer_rotation 0 > /dev/null
        sh_ settings put system user_rotation 1 > /dev/null
        check_step "$pkg" "화면 다시 만들기(가로)" 4 || rc=1
        sh_ settings put system user_rotation 0 > /dev/null
        check_step "$pkg" "화면 다시 만들기(세로)" 4 || rc=1
        rotate_restore
    fi

    if [ $rc = 0 ]; then
        sh_ input keyevent KEYCODE_HOME
        check_step "$pkg" "홈으로 (화면 안 보임)" 3 0 || rc=1
        launch "다시 열기" "$comp"
        check_step "$pkg" "다시 열기" 4 || rc=1
    fi

    if [ $rc = 0 ]; then
        sh_ am force-stop "$pkg"
        launch "강제 종료 후 다시 실행" "$comp"
        check_step "$pkg" "강제 종료 후 다시 실행 (저장된 설정으로)" 8 || rc=1
    fi

    [ $rc = 0 ] || save_evidence "$tag"
    sh_ am force-stop "$pkg"
    sh_ input keyevent KEYCODE_HOME
    if [ $installed_before = 0 ] && [ $KEEP = 0 ]; then
        adb_ uninstall "$pkg" > /dev/null 2>&1 && note "검사용으로 깐 앱을 지움"
    fi
    return $rc
}

trap rotate_restore EXIT
passed=() failed=() skipped=()
for apk in "${APKS[@]}"; do
    smoke_one "$apk"
    case $? in
        0) passed+=("$(basename "$apk")") ;;
        3) skipped+=("$(basename "$apk")") ;;
        *) failed+=("$(basename "$apk")") ;;
    esac
done

echo
echo "시작 검사 요약 ($DEVICE)"
echo "  통과 ${#passed[@]}: ${passed[*]:-}"
echo "  건너뜀 ${#skipped[@]}: ${skipped[*]:-}"
echo "  실패 ${#failed[@]}: ${failed[*]:-}"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### 시작 검사 ($DEVICE)"
        for a in ${passed[@]+"${passed[@]}"}; do echo "- 통과: $a"; done
        for a in ${skipped[@]+"${skipped[@]}"}; do echo "- 건너뜀 (CPU 가 맞지 않음): $a"; done
        for a in ${failed[@]+"${failed[@]}"}; do echo "- **실패: $a**"; done
    } >> "$GITHUB_STEP_SUMMARY"
fi
[ ${#failed[@]} = 0 ]
