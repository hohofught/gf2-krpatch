#!/usr/bin/env python3
"""
검토기: 이 저장소에서 실제로 사고가 났던 유형을 소스에서 기계적으로 찾는다.
검토를 대신하지 않는다. 무엇을 보는지와 못 보는지는 docs/review-guide.md.

  python3 tools/review/check_source.py                  # 규칙 init-order (전체 Kotlin 소스)
  python3 tools/review/check_source.py --diff origin/main   # + engine-parity (그 커밋과 비교해 바뀐 파일)
  python3 tools/review/check_source.py --release-tag v2.3   # + release-version (태그와 버전 번호)

오류가 하나라도 있으면 종료 코드 1. 경고는 0 으로 끝난다.
GitHub Actions 안이면 ::error / ::warning 으로 찍어 PR 화면의 해당 줄에 붙게 한다.
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IN_CI = os.environ.get("GITHUB_ACTIONS") == "true"

errors = 0
warnings = 0


def rel(path):
    p = Path(path).resolve()
    return p.relative_to(ROOT).as_posix() if p.is_relative_to(ROOT) else p.as_posix()


def report(level, rule, msg, path=None, line=None):
    global errors, warnings
    if level == "error":
        errors += 1
    else:
        warnings += 1
    if IN_CI:
        loc = " file=%s%s," % (rel(path), ",line=%d" % line if line else "") if path else " "
        print("::%s%stitle=%s::%s" % (level, loc, rule, msg))
    else:
        where = rel(path) + (":%d" % line if line else "") + "  " if path else ""
        print("%s [%s] %s%s" % ("오류" if level == "error" else "경고", rule, where, msg))


# ---------------------------------------------------------------------------
# init-order: 초기값 있는 필드가 init 블록 아래에 있으면 안 된다
#
# Kotlin 은 필드 초기화와 init 블록을 적힌 순서대로 돈다. init 이 부른 함수가 아래 필드를 읽으면 그 필드는 아직
# null(참조형)이거나 0 이다. 컴파일러는 init 안에서 직접 읽을 때만 경고하고, 함수를 거치면 아무 말도 없다.
# 2.2 안드로이드: init -> refresh() -> withStored() -> diskStats(null) 로 켜자마자 죽었다.
# 그래서 "init 을 쓰는 클래스는 초기값 있는 필드를 모두 init 위에" 를 규칙으로 둔다 (Kotlin 코딩 규약의 순서와도 같다).
# 일부러 아래에 둔 것이면 그 줄이나 바로 윗줄에 `check:allow init-order` 주석을 단다.
# ---------------------------------------------------------------------------

INIT_RE = re.compile(r"^(\s*)init\s*\{")
MODS = r"(?:(?:private|protected|internal|public|override|open|final|const|lateinit|abstract|actual|expect)\s+)*"
ANNOT = r"(?:@[\w.]+(?:\([^)]*\))?\s+)*"
PROP_RE = re.compile(r"^(\s*)" + ANNOT + MODS + r"(?:val|var)\s+(`[^`]+`|\w+)(.*)$")
ALLOW = "check:allow init-order"


def has_initializer(rest):
    """val/var 이름 뒤 부분에 초기값(= 또는 by 위임)이 있는가. get()/set() 접근자만 있는 속성은 아니다"""
    code = rest.split("//", 1)[0]
    # 타입에 함수 타입(->)이나 제네릭이 있어도 = 앞의 get( 이 있으면 접근자다
    eq = re.search(r"(?<![=!<>])=(?!=)|\bby\b", code)
    if not eq:
        return False
    return not re.search(r"\bget\s*\(", code[: eq.start()])


def check_init_order(files):
    for path in files:
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            m = INIT_RE.match(line)
            if not m:
                continue
            indent = m.group(1)
            # init 과 같은 깊이(같은 클래스 몸체)의 줄만 본다. 더 얕은 줄이 나오면 클래스가 끝난 것
            for j in range(i + 1, len(lines)):
                cur = lines[j]
                if not cur.strip():
                    continue
                cur_indent = len(cur) - len(cur.lstrip())
                if cur_indent < len(indent):
                    break
                if cur_indent != len(indent):
                    continue
                p = PROP_RE.match(cur)
                if not p or not has_initializer(p.group(3)):
                    continue
                above = lines[j - 1].strip()
                if ALLOW in cur or (above.startswith("//") and ALLOW in above):
                    continue
                report(
                    "error",
                    "init-order",
                    "필드 '%s' 가 %d번 줄 init 블록보다 아래에 있다. init 이 부르는 함수가 읽으면 아직 초기화 전이다 "
                    "(2.2 시작 크래시와 같은 유형). init 위로 옮길 것" % (p.group(2), i + 1),
                    path,
                    j + 1,
                )


# ---------------------------------------------------------------------------
# engine-parity: 세 엔진(Kotlin·C++·C#) 중 일부만 바뀌었으면 경고
#
# 세 엔진은 같은 입력에 같은 결과를 내야 한다 (docs/lang-table-format.md). 한쪽만 고치면 안드로이드 두 판과
# 윈도우가 다른 한패를 만든다. 형식과 무관한 수정(로그 문구 등)일 수 있어 경고로만 둔다.
# ---------------------------------------------------------------------------

ENGINES = {
    "Kotlin": ("app/src/main/java/com/hoho/snqxkr/langtable/",),
    "C++": ("native/engine.cpp", "native/snqx.h", "native/sha256.cpp", "native/sha256.h"),
    "C#": ("windows/SnqxKR.Windows/Engine/",),
}
# 엔진 고르기·JNI·P/Invoke 연결만 하는 파일은 결과에 영향이 없다
ENGINE_GLUE = (
    "app/src/main/java/com/hoho/snqxkr/langtable/Engines.kt",
    "windows/SnqxKR.Windows/Engine/Engines.cs",
    "windows/SnqxKR.Windows/Engine/NativeEngine.cs",
)


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")


def check_engine_parity(base):
    # 갈라진 지점과 작업 트리를 비교한다 (커밋 전에 돌려도 보이게. CI 에서는 작업 트리 = HEAD)
    mb = git("merge-base", base, "HEAD")
    r = git("diff", "--name-only", mb.stdout.strip() if mb.returncode == 0 else base)
    if r.returncode != 0:
        report("warning", "engine-parity", "비교할 커밋 %s 를 찾지 못해 건너뜀 (%s)" % (base, r.stderr.strip()))
        return
    changed = [f for f in r.stdout.splitlines() if f and f not in ENGINE_GLUE]
    touched = {name for name, prefixes in ENGINES.items() if any(f.startswith(p) for f in changed for p in prefixes)}
    if touched and touched != set(ENGINES):
        missing = [n for n in ENGINES if n not in touched]
        report(
            "warning",
            "engine-parity",
            "번역 엔진이 %s 쪽만 바뀌었다 (%s 그대로). 세 엔진 결과가 같은지 확인했는지 검토할 것 "
            "(형식과 무관한 수정이면 무시)" % ("·".join(n for n in ENGINES if n in touched), "·".join(missing)),
        )


# ---------------------------------------------------------------------------
# release-version: 태그와 버전 번호가 맞는가
#
# 안드로이드 versionCode 가 그대로면 설치된 앱 위로 업데이트가 안 된다. 윈도우 Version 도 같이 올린다.
# ---------------------------------------------------------------------------

GRADLE = "app/build.gradle.kts"
CSPROJ = "windows/SnqxKR.Windows/SnqxKR.Windows.csproj"


def read_versions(text):
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return (int(code.group(1)) if code else None, name.group(1) if name else None)


def check_release(tag):
    want = tag[1:] if tag.startswith("v") else tag
    code, name = read_versions((ROOT / GRADLE).read_text(encoding="utf-8"))
    if name != want:
        report("error", "release-version", "태그 %s 인데 versionName 이 %s" % (tag, name), ROOT / GRADLE)
    win = re.search(r"<Version>([^<]+)</Version>", (ROOT / CSPROJ).read_text(encoding="utf-8"))
    if not win or not (win.group(1) == want or win.group(1).startswith(want + ".")):
        report("error", "release-version", "태그 %s 인데 윈도우 Version 이 %s" % (tag, win.group(1) if win else "없음"), ROOT / CSPROJ)
    # 태그를 달기 전에 미리 돌리면 HEAD 에서 닿는 마지막 태그와 비교한다
    exists = git("rev-parse", "-q", "--verify", "refs/tags/" + tag).returncode == 0
    prev = git("describe", "--tags", "--abbrev=0", "--match", "v*", tag + "^" if exists else "HEAD")
    if prev.returncode != 0:
        report("warning", "release-version", "이전 태그를 찾지 못해 versionCode 비교를 건너뜀 (fetch-depth: 0 인지 확인)")
        return
    prev_tag = prev.stdout.strip()
    old = git("show", prev_tag + ":" + GRADLE)
    prev_code = read_versions(old.stdout)[0] if old.returncode == 0 else None
    if code is None or prev_code is None or code <= prev_code:
        report(
            "error",
            "release-version",
            "versionCode %s 가 이전 태그 %s 의 %s 보다 커야 한다 (같으면 업데이트 설치가 안 된다)" % (code, prev_tag, prev_code),
            ROOT / GRADLE,
        )


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # 윈도우 콘솔 기본(cp949)으로는 한글이 깨진다
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--diff", metavar="BASE", help="이 커밋과 비교해 바뀐 파일로 engine-parity 를 본다")
    ap.add_argument("--release-tag", metavar="TAG", help="릴리스 태그 (예: v2.3) 와 버전 번호를 맞춰 본다")
    ap.add_argument("paths", nargs="*", help="init-order 를 볼 파일·폴더 (기본: app/src)")
    a = ap.parse_args()

    roots = [Path(p) for p in a.paths] or [ROOT / "app/src"]
    files = []
    for r in roots:
        files += [r] if r.is_file() else sorted(r.rglob("*.kt"))
    check_init_order(files)
    if a.diff:
        check_engine_parity(a.diff)
    if a.release_tag:
        check_release(a.release_tag)

    print("검토기: Kotlin 파일 %d개, 오류 %d, 경고 %d" % (len(files), errors, warnings))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
