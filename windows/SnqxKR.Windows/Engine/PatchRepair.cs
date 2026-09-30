using System;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace SnqxKR.Engine
{
    /// <summary>
    /// 게임 업데이트로 한패가 맞지 않게 됐을 때, 새 공식 원문을 다시 한국어로 만든다 (안드로이드 PatchRepair.kt 와 같은 동작).
    ///  1단계: 번역 메모리에서 문장을 원문으로 찾아 바꾼다. 메모리에 없는 문장(새 콘텐츠)은 중국어로 둔다.
    ///  2단계: 업데이트 전에 쓰던 한패가 메모리보다 새것이면, 그 번역을 새 Id 자리로 옮긴다 (<see cref="Overlay"/>).
    /// 결과 문장은 복사하지 않고 원문·메모리·옛 한패 파일 안의 자리로 든다 (<see cref="Texts.Builder"/>).
    /// 색인은 LangTable 이 쓸 때 다시 계산한다.
    /// </summary>
    public static class PatchRepair
    {
        public sealed class Result
        {
            public Result(LangTable table, int translated, int leftChinese, int fromPatch)
            {
                Table = table;
                Translated = translated;
                LeftChinese = leftChinese;
                FromPatch = fromPatch;
            }

            public LangTable Table { get; }
            /// <summary>한국어가 된 문장 (번역 메모리 + 옛 한패)</summary>
            public int Translated { get; }
            /// <summary>한자가 있는데 한국어로 못 바꿔 중국어로 남은 문장</summary>
            public int LeftChinese { get; }
            /// <summary>그중 옛 한패에서 옮긴 문장 (메모리에 없던 것, 메모리보다 새 표현)</summary>
            public int FromPatch { get; }
            public double Coverage => (double)Translated / Math.Max(1, Translated + LeftChinese);
        }

        // 결과 문장이 어디서 왔는지 (Texts.Builder 의 parts 순서)
        private const int Official = 0;
        private const int Memory = 1;
        private const int OldPatch = 2;

        public static Result Repair(LangTable official, TranslationMemory tm, LangTable? oldPatch = null)
        {
            int n = official.Count;
            var zh = official.Texts;
            var mt = tm.Texts;
            var parts = oldPatch != null ? new[] { zh, mt, oldPatch.Texts } : new[] { zh, mt };
            var output = new Texts.Builder(parts, n);
            var order = official.SortedOrder();
            // 2단계용: Id 순 k 번째 줄의 1단계 결과 키. Id 순으로 돌며 바로 그 자리에 쓴다 (따로 옮겨 담지 않는다).
            // 원문 그대로인 줄은 찾을 때 구한 해시를 다시 쓴다
            var keys = oldPatch != null ? new long[n] : null;
            var han = new bool[n]; // 원문 그대로 둔 줄에 한자가 있는지
            for (int k = 0; k < n; k++)
            {
                int i = order[k];
                byte[] b = zh.Buf(i);
                int off = zh.Off[i], len = zh.Len[i];
                long h = TranslationMemory.Hash(b, off, len);
                int j = tm.Find(h);
                if (j >= 0)
                {
                    output.Set(i, Memory, mt, j);
                    if (keys != null) keys[k] = Key(mt, j);
                }
                else
                {
                    output.Set(i, Official, zh, i);
                    han[i] = HasHan(b, off, len);
                    if (keys != null) keys[k] = len == 0 ? 0 : NonZero(h);
                }
            }
            int fromPatch = oldPatch != null && keys != null ? Overlay(official, output, keys, oldPatch) : 0;
            int translated = 0, leftChinese = 0;
            for (int i = 0; i < n; i++)
            {
                if (!output.IsFrom(i, Official, zh)) translated++;
                else if (han[i]) leftChinese++;
            }
            return new Result(new LangTable(official.Ids, output.Build(n), official.ChunkSize, official.HeaderFlag), translated, leftChinese, fromPatch);
        }

        /// <summary>
        /// 한패가 같은 게임 버전 공식 원문과 같은 자리(Id)에 번역을 넣었는지.
        /// 한패에서 번역하지 않은 줄(한글 없이 한자만)은 같은 Id 의 원문과 똑같아야 한다.
        /// 실측: 정상 한패 100%, 업데이트 직후 올라온 깨진 한패 0%(09-22)·78%(08-11).
        /// </summary>
        public sealed class Alignment
        {
            public Alignment(int untranslated, int matching) { Untranslated = untranslated; Matching = matching; }
            public int Untranslated { get; }
            public int Matching { get; }
            public double Ratio => Untranslated == 0 ? 1.0 : (double)Matching / Untranslated;
            /// <summary>판단할 만큼 줄이 있고 <see cref="MinAlignment"/> 이상 맞음. 줄이 너무 적으면 판단 못 함 (null)</summary>
            public bool? Ok => Untranslated < MinUntranslated ? (bool?)null : Ratio >= MinAlignment;
        }

        public const double MinAlignment = 0.90;
        private const int MinUntranslated = 20;

        public static Alignment Align(LangTable official, LangTable patch)
        {
            if (!official.SameIds(patch)) throw new InvalidOperationException("공식 원문과 한패의 게임 버전이 다릅니다");
            var oOrd = official.SortedOrder();
            var pOrd = patch.SortedOrder();
            var kt = patch.Texts;
            int untranslated = 0, matching = 0;
            for (int k = 0; k < oOrd.Length; k++)
            {
                int j = pOrd[k];
                byte[] b = kt.Buf(j);
                if (HasHangul(b, kt.Off[j], kt.Len[j]) || !HasHan(b, kt.Off[j], kt.Len[j])) continue;
                untranslated++;
                if (Texts.Same(kt, j, official.Texts, oOrd[k])) matching++;
            }
            return new Alignment(untranslated, matching);
        }

        private const int MinRun = 3;
        private const int MaxRun = 64;
        private const int MaxCandidates = 16;

        /// <summary>
        /// 2단계. Id 는 업데이트마다 다시 매겨지지만 연속된 몇 줄(대사 한 장면 등)이 함께 움직인다.
        /// 1단계 결과(output)와 옛 한패를 Id 순으로 늘어놓고, 같은 문장이 3줄 이상 이어지는 곳에서
        /// 옛 줄 → 새 줄 대응을 정한다. 후보가 여럿이면 가장 길게 이어지는 한 곳만 쓰고, 동률이면 버린다.
        /// 대응된 두 줄 사이가 양쪽 모두 끊김 없이 같은 길이면 그 사이 줄도 같은 차이로 옮긴다.
        /// 옮길 번역이 새 원문과 태그·자리표시자·숫자가 다르면 쓰지 않는다 (예: 150% → 180% 로 바뀐 스킬 설명).
        /// 메모리가 이미 이 한패를 담고 있으면 얻는 게 없고 바뀐 원문에 옛 번역을 얹게 되므로 부르지 않는다.
        /// </summary>
        /// <param name="nh">Id 순 b 번째 줄의 1단계 결과 문장 키 (<see cref="Key"/>). Id 는 복사하지 않고 "Id 순 다음 줄과 1 차이인지" 만 든다</param>
        /// <returns>output 에서 바꾼 줄 수</returns>
        private static int Overlay(LangTable official, Texts.Builder output, long[] nh, LangTable oldPatch)
        {
            var nOrd = official.SortedOrder();
            var oOrd = oldPatch.SortedOrder();
            var ot = oldPatch.Texts;
            int N = nOrd.Length, O = oOrd.Length;
            var nNext = new bool[N];
            for (int b = 0; b + 1 < N; b++) nNext[b] = official.Ids[nOrd[b + 1]] == official.Ids[nOrd[b]] + 1;
            var oNext = new bool[O];
            for (int a = 0; a + 1 < O; a++) oNext[a] = oldPatch.Ids[oOrd[a + 1]] == oldPatch.Ids[oOrd[a]] + 1;
            var oh = oOrd.Select(i => Key(ot, i)).ToArray();

            // 새 쪽 문장 색인: 해시 상위 비트에 위치를 붙여 정렬 (Dictionary 없이)
            int posBits = 1;
            while ((1L << posBits) <= nh.Length) posBits++;
            long low = (1L << posBits) - 1;
            var packed = new long[nh.Length];
            for (int i = 0; i < nh.Length; i++) packed[i] = (nh[i] & ~low) | (long)i;
            Array.Sort(packed);

            bool NextOk(int a, int b) => a + 1 < O && b + 1 < N && oNext[a] && nNext[b] && oh[a + 1] != 0 && oh[a + 1] == nh[b + 1];
            bool PrevOk(int a, int b) => a > 0 && b > 0 && oNext[a - 1] && nNext[b - 1] && oh[a - 1] != 0 && oh[a - 1] == nh[b - 1];

            // 옛 줄마다 가장 길게 이어지는 새 자리
            var matchTo = Enumerable.Repeat(-1, O).ToArray();
            for (int a = 0; a < O; a++)
            {
                long h = oh[a];
                if (h == 0) continue;
                long hi = h & ~low;
                int first = LowerBound(packed, hi), k = first;
                while (k < packed.Length && (packed[k] & ~low) == hi) k++;
                if (k - first > MaxCandidates) continue;
                int best = 0, second = 0, bestB = -1;
                for (int c = first; c < k; c++)
                {
                    int b = (int)(packed[c] & low);
                    if (nh[b] != h) continue;
                    int run = 1, x = a, y = b;
                    while (run < MaxRun && PrevOk(x, y)) { x--; y--; run++; }
                    x = a; y = b;
                    while (run < 2 * MaxRun && NextOk(x, y)) { x++; y++; run++; }
                    if (run > best) { second = best; best = run; bestB = b; }
                    else if (run > second) second = run;
                }
                if (best >= MinRun && best > second) matchTo[a] = bestB;
            }

            // 새 자리 → 옛 줄. 두 줄이 한 자리로 오면 둘 다 쓰지 않는다.
            var target = Enumerable.Repeat(-1, N).ToArray();
            void Put(int a, int b) => target[b] = target[b] == -1 ? a : -2;
            int last = -1;
            for (int a = 0; a < O; a++)
            {
                int b = matchTo[a];
                if (b < 0) continue;
                Put(a, b);
                if (last >= 0)
                {
                    int lb = matchTo[last], gap = a - last;
                    if (gap > 1 && b - lb == gap && oldPatch.Ids[oOrd[a]] - oldPatch.Ids[oOrd[last]] == gap &&
                        official.Ids[nOrd[b]] - official.Ids[nOrd[lb]] == gap)
                        for (int t = 1; t < gap; t++) Put(last + t, lb + t);
                }
                last = a;
            }

            int changed = 0;
            var cur = output.View;
            var zh = official.Texts;
            for (int b = 0; b < N; b++)
            {
                int a = target[b];
                if (a < 0) continue;
                int j = oOrd[a], k = nOrd[b];
                byte[] ko = ot.Buf(j);
                if (!HasHangul(ko, ot.Off[j], ot.Len[j]) || Texts.Same(ot, j, cur, k)) continue;
                if (Shape(ko, ot.Off[j], ot.Len[j]) != Shape(zh.Buf(k), zh.Off[k], zh.Len[k])) continue;
                output.Set(k, OldPatch, ot, j);
                changed++;
            }
            return changed;
        }

        /// <summary>빈 문장은 0 (어떤 문장과도 맞지 않는 것으로 친다)</summary>
        private static long Key(Texts t, int i)
        {
            int len = t.Len[i];
            return len == 0 ? 0 : NonZero(TranslationMemory.Hash(t.Buf(i), t.Off[i], len));
        }

        private static long NonZero(long h) => h == 0 ? 1 : h;

        private static int LowerBound(long[] a, long v)
        {
            int lo = 0, hi = a.Length;
            while (lo < hi)
            {
                int mid = lo + (hi - lo) / 2;
                if (a[mid] < v) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        private static readonly Regex ShapeToken = new Regex(@"\{[0-9]+\}|%[0-9.]*[sdf]|<[^<>]{1,40}>|\\n|\n|[0-9]+(?:\.[0-9]+)?", RegexOptions.CultureInvariant);

        /// <summary>번역해도 그대로 남아야 하는 것: 태그, 자리표시자, 줄바꿈, 숫자 (순서 무관)</summary>
        internal static string Shape(byte[] utf8) => Shape(utf8, 0, utf8.Length);

        /// <summary>b 의 off 부터 len 바이트</summary>
        internal static string Shape(byte[] b, int off, int len)
        {
            var tokens = ShapeToken.Matches(DecodeUtf8(b, off, len)).Cast<Match>().Select(m => m.Value).ToList();
            tokens.Sort(StringComparer.Ordinal);
            return string.Join("\u0001", tokens);
        }

        internal static string DecodeUtf8(byte[] b) => DecodeUtf8(b, 0, b.Length);

        /// <summary>
        /// UTF-8 → UTF-16 (b 의 off 부터 len 바이트). 깨진 바이트는 한 바이트마다 U+FFFD.
        /// 플랫폼 디코더(JVM·.NET)는 깨진 바이트를 서로 다르게 바꾸므로 Kotlin·C#·C++ 가 같은 규칙을 직접 쓴다.
        /// </summary>
        internal static string DecodeUtf8(byte[] b, int off, int len)
        {
            int end = off + len;
            var sb = new StringBuilder(len);
            bool Cont(int k) => k < end && (b[k] & 0xC0) == 0x80;
            int At(int k) => b[k] & 0x3F;
            int i = off;
            while (i < end)
            {
                int c = b[i];
                if (c < 0x80) { sb.Append((char)c); i++; continue; }
                if (c >= 0xC2 && c <= 0xDF && Cont(i + 1)) { sb.Append((char)(((c & 0x1F) << 6) | At(i + 1))); i += 2; continue; }
                if (c >= 0xE0 && c <= 0xEF && Cont(i + 1) && Cont(i + 2))
                {
                    sb.Append((char)(((c & 0x0F) << 12) | (At(i + 1) << 6) | At(i + 2)));
                    i += 3;
                    continue;
                }
                if (c >= 0xF0 && c <= 0xF4 && Cont(i + 1) && Cont(i + 2) && Cont(i + 3))
                {
                    int cp = ((c & 0x07) << 18) | (At(i + 1) << 12) | (At(i + 2) << 6) | At(i + 3);
                    if (cp <= 0x10FFFF)
                    {
                        if (cp < 0x10000) sb.Append((char)cp);
                        else { cp -= 0x10000; sb.Append((char)(0xD800 + (cp >> 10))); sb.Append((char)(0xDC00 + (cp & 0x3FF))); }
                        i += 4;
                        continue;
                    }
                }
                sb.Append('�');
                i++;
            }
            return sb.ToString();
        }

        public static bool HasHan(byte[] utf8) => HasHan(utf8, 0, utf8.Length);

        /// <summary>UTF-8(b 의 off 부터 len 바이트) 안에 CJK 통합 한자(U+4E00..U+9FFF)가 있는지</summary>
        public static bool HasHan(byte[] b, int off, int len)
        {
            int end = off + len;
            for (int i = off; i + 2 < end;)
            {
                int b0 = b[i];
                if (b0 >= 0xE4 && b0 <= 0xE9)
                {
                    int cp = ((b0 & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F);
                    if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
                    i += 3;
                }
                else i++;
            }
            return false;
        }

        public static bool HasHangul(byte[] utf8) => HasHangul(utf8, 0, utf8.Length);

        /// <summary>UTF-8(b 의 off 부터 len 바이트) 안에 한글 음절(U+AC00..U+D7A3)이 있는지</summary>
        public static bool HasHangul(byte[] b, int off, int len)
        {
            int end = off + len;
            for (int i = off; i + 2 < end;)
            {
                int b0 = b[i];
                if (b0 >= 0xEA && b0 <= 0xED)
                {
                    int cp = ((b0 & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F);
                    if (cp >= 0xAC00 && cp <= 0xD7A3) return true;
                    i += 3;
                }
                else i++;
            }
            return false;
        }
    }
}
