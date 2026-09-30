using System;
using System.IO;
using System.Linq;

namespace SnqxKR.Engine
{
    /// <summary>
    /// 중국어 원문 → 한국어 번역 (안드로이드 TranslationMemory.kt 와 같은 동작·파일 형식).
    /// 게임 업데이트마다 Id 가 전부 다시 매겨지므로 원문으로 찾는다. 원문은 64비트 해시로만 들고 있고,
    /// 번역은 복사하지 않고 파일 내용 안의 자리만 든다 (<see cref="Engine.Texts"/>).
    /// 줄마다 세대(몇 번째로 합친 한패에서 마지막으로 봤는지)를 들고 있고, 파일이 <see cref="MaxBytes"/> 를 넘으면
    /// 가장 오래전에 본 줄(게임에서 사라진 문장일 가능성이 큰 것)부터 뺀다.
    /// </summary>
    public sealed class TranslationMemory
    {
        /// <summary>번역 메모리 파일 최대 크기. 지금 속도(업데이트마다 1~2MB)로는 몇 년 걸린다.</summary>
        public const long MaxBytes = 500L * 1024 * 1024;

        private const int MagicV1 = 0x534e514d; // "SNQM": 세대 없음
        private const int MagicV2 = 0x534e5132; // "SNQ2"
        private const long HeaderBytes = 8;
        private const long EntryBytes = 16;     // 해시 8 + 세대 4 + 길이 4

        private readonly long[] keys;     // 오름차순
        private readonly Texts values;
        private readonly int[] gens;
        private readonly Lazy<int[]?> buckets; // Find 용 칸 색인 (BucketIndex). 처음 찾을 때 만든다

        private TranslationMemory(long[] keys, Texts values, int[] gens)
        {
            this.keys = keys;
            this.values = values;
            this.gens = gens;
            buckets = new Lazy<int[]?>(() => BucketIndex(keys));
        }

        public int Count => keys.Length;

        /// <summary>번역 문장들 (줄 번호는 <see cref="Find"/> 가 돌려준 것)</summary>
        internal Texts Texts => values;

        /// <summary>파일로 썼을 때 크기</summary>
        public long ByteSize
        {
            get
            {
                long total = HeaderBytes;
                for (int i = 0; i < Count; i++) total += EntryBytes + values.Length(i);
                return total;
            }
        }

        /// <summary>원문 해시(<see cref="Hash(byte[], int, int)"/>)로 찾은 줄 번호. 없으면 음수</summary>
        public int Find(long hash)
        {
            var b = buckets.Value;
            int d = BucketOf(hash);
            int from = b != null ? b[d] : 0;
            int to = b != null ? b[d + 1] : keys.Length;
            int i = LowerBound(keys, from, to, hash);
            return i < to && keys[i] == hash ? i : -1;
        }

        /// <summary>칸 색인은 해시 상위 16비트로 나눈다 (65,537칸, 256KB)</summary>
        private const int BucketBits = 16;

        /// <summary>v 가 들어갈 칸. 부호 비트를 뒤집어 부호 있는 순서를 그대로 따른다</summary>
        internal static int BucketOf(long v) => (int)((ulong)(v ^ long.MinValue) >> (64 - BucketBits));

        /// <summary>
        /// 오름차순 배열의 칸 색인: 칸 d 의 값은 a[r[d], r[d+1]) 에 있다. 해시는 고르게 퍼져 한 칸에 몇 개뿐이라
        /// 이진 탐색이 전체(27만 줄이면 18단계) 대신 칸 안(2~3단계)에서 끝나고, 찾은 자리는 전체에서 찾은 것과 같다.
        /// 엄격히 오름차순이 아니면(깨진 메모리 파일) null: 전체에서 <see cref="LowerBound"/> 로 찾는다.
        /// </summary>
        internal static int[]? BucketIndex(long[] a)
        {
            var r = new int[(1 << BucketBits) + 1];
            for (int i = 0; i < a.Length; i++)
            {
                if (i > 0 && a[i] <= a[i - 1]) return null;
                r[BucketOf(a[i]) + 1]++;
            }
            for (int d = 0; d < 1 << BucketBits; d++) r[d + 1] += r[d];
            return r;
        }

        /// <summary>
        /// a[from, to) 에서 v 이상인 첫 자리 (없으면 to). Kotlin·C++ 와 같은 순서로 반씩 나눠, 정렬이 깨진 배열에서도
        /// 세 엔진이 같은 자리를 낸다 (Array.BinarySearch 는 나누는 방식이 달라 깨진 파일에서 다른 줄을 찾았다).
        /// </summary>
        internal static int LowerBound(long[] a, int from, int to, long v)
        {
            int lo = from, hi = to;
            while (lo < hi)
            {
                int mid = lo + (hi - lo) / 2;
                if (a[mid] < v) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        /// <summary>원문으로 찾은 번역 (복사본. 시험·작은 곳용)</summary>
        public byte[]? Get(byte[] source)
        {
            int i = Find(Hash(source));
            return i >= 0 ? values[i] : null;
        }

        /// <summary>두 메모리를 합친다. 같은 원문이면 newer 의 번역이 이기고, newer 의 줄은 새 세대가 된다.</summary>
        public TranslationMemory MergedWith(TranslationMemory newer)
        {
            int gen = (gens.Length == 0 ? -1 : gens.Max()) + 1;
            int n = Count + newer.Count;
            var k = new long[n];
            var v = new Texts.Builder(new[] { values, newer.values }, n);
            var g = new int[n];
            int i = 0, j = 0, o = 0;
            while (i < Count || j < newer.Count)
            {
                bool takeNew = i >= Count || (j < newer.Count && newer.keys[j] <= keys[i]);
                if (takeNew)
                {
                    if (i < Count && keys[i] == newer.keys[j]) i++;
                    k[o] = newer.keys[j]; v.Set(o, 1, newer.values, j); g[o] = gen; j++;
                }
                else
                {
                    k[o] = keys[i]; v.Set(o, 0, values, i); g[o] = gens[i]; i++;
                }
                o++;
            }
            Array.Resize(ref k, o);
            Array.Resize(ref g, o);
            return new TranslationMemory(k, v.Build(o), g);
        }

        /// <summary>파일 크기가 maxBytes 이하가 되도록 오래된 세대부터 뺀다</summary>
        public TranslationMemory Capped(long maxBytes = MaxBytes)
        {
            long total = ByteSize;
            if (total <= maxBytes) return this;
            var drop = new bool[Count];
            foreach (var gen in gens.Distinct().OrderBy(x => x))
            {
                for (int i = 0; i < keys.Length && total > maxBytes; i++)
                {
                    if (gens[i] != gen) continue;
                    drop[i] = true;
                    total -= EntryBytes + values.Length(i);
                }
                if (total <= maxBytes) break;
            }
            var keep = Enumerable.Range(0, Count).Where(i => !drop[i]).ToArray();
            return new TranslationMemory(keep.Select(i => keys[i]).ToArray(), values.Select(keep, keep.Length), keep.Select(i => gens[i]).ToArray());
        }

        public void WriteTo(string path)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            using var w = new BinaryWriter(new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16));
            // 안드로이드(DataOutputStream)와 같은 빅엔디안으로 쓴다
            WriteBE(w, MagicV2);
            WriteBE(w, keys.Length);
            for (int i = 0; i < keys.Length; i++)
            {
                WriteBE(w, keys[i]);
                WriteBE(w, gens[i]);
                WriteBE(w, values.Len[i]);
                w.Write(values.Buf(i), values.Off[i], values.Len[i]);
            }
        }

        /// <summary>
        /// 파일을 한 번에 읽는다. 번역은 복사하지 않고 그 안의 자리만 든다.
        /// 첫 4바이트로 형식을 가린다: 0.2 윈도우판은 리틀엔디안 "SNQM" 을 썼고, 이제는 안드로이드와 같은 빅엔디안이다.
        /// 깨졌거나 잘린 파일은 <see cref="InvalidDataException"/> 하나로 알린다 (Kotlin CorruptMemoryException·C++ Corrupt 와 같다).
        /// </summary>
        public static TranslationMemory Read(string path)
        {
            var b = File.ReadAllBytes(path);
            int p = 0;
            void Need(int k) { if (k > b.Length - p) throw new InvalidDataException("번역 메모리 파일이 끊김"); }
            Need(4);
            int be = (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
            int le = b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24);
            bool bigEndian = be == MagicV1 || be == MagicV2;
            int magic = bigEndian ? be : le;
            if (magic != MagicV1 && magic != MagicV2) throw new InvalidDataException("번역 메모리 파일이 아닙니다");
            p = 4;
            int U32()
            {
                Need(4);
                int v = bigEndian ? (b[p] << 24) | (b[p + 1] << 16) | (b[p + 2] << 8) | b[p + 3]
                                  : b[p] | (b[p + 1] << 8) | (b[p + 2] << 16) | (b[p + 3] << 24);
                p += 4;
                return v;
            }
            long U64()
            {
                long a = (uint)U32(), c = (uint)U32();
                return bigEndian ? (a << 32) | c : (c << 32) | a;
            }
            int n = U32();
            // 줄마다 12바이트 이상이므로 파일 크기로 줄 수를 확인한다 (깨진 파일에 큰 배열을 잡지 않게)
            long left = b.Length - HeaderBytes;
            if (n < 0 || n > left / 12) throw new InvalidDataException("번역 메모리 줄 수가 이상함");
            var keys = new long[n];
            var gens = new int[n];
            var off = new int[n];
            var len = new int[n];
            for (int i = 0; i < n; i++)
            {
                keys[i] = U64();
                if (magic == MagicV2) gens[i] = U32();
                int l = U32();
                left -= (magic == MagicV2 ? EntryBytes : 12) + (long)l;
                if (l < 0 || left < 0) throw new InvalidDataException("번역 메모리 파일이 끊김");
                off[i] = p;
                len[i] = l;
                p += l;
            }
            return new TranslationMemory(keys, new Texts(new[] { b }, null, off, len), gens);
        }

        private static void WriteBE(BinaryWriter w, int v) => w.Write(new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v });

        private static void WriteBE(BinaryWriter w, long v)
        {
            var b = new byte[8];
            for (int i = 7; i >= 0; i--) { b[i] = (byte)v; v >>= 8; }
            w.Write(b);
        }

        /// <summary>
        /// 같은 게임 버전의 공식 원문과 한패를 같은 Id 끼리 짝지어 만든다.
        /// 한패가 원문을 그대로 둔 문장(미번역, 숫자·기호)은 넣지 않는다. 번역은 한패 파일 안의 자리로 든다.
        /// </summary>
        public static TranslationMemory Build(LangTable official, LangTable patch)
        {
            if (!official.SameIds(patch)) throw new InvalidOperationException("공식 원문과 한패의 게임 버전이 다릅니다");
            int n = official.Count;
            // 공식 원문 i 번째 줄과 같은 Id 인 한패 줄 (둘 다 Id 순으로 늘어놓고 짝짓는다)
            var oOrd = official.SortedOrder();
            var pOrd = patch.SortedOrder();
            var patchAt = new int[n];
            for (int k = 0; k < n; k++) patchAt[oOrd[k]] = pOrd[k];
            var zh = official.Texts;
            var hashes = new long[n];
            var src = new int[n];
            int m = 0;
            for (int i = 0; i < n; i++)
            {
                if (Texts.Same(patch.Texts, patchAt[i], zh, i)) continue;
                hashes[m] = Hash(zh.Buf(i), zh.Off[i], zh.Len[i]);
                src[m] = patchAt[i];
                m++;
            }
            // 해시 순으로 정렬하되 같은 원문은 공식 원문에서 먼저 나온 줄의 번역을 쓴다 (안정 정렬)
            RadixSort(hashes, src, m);
            int u = 0;
            for (int i = 0; i < m; i++)
            {
                if (u > 0 && hashes[i] == hashes[u - 1]) continue;
                hashes[u] = hashes[i];
                src[u] = src[i];
                u++;
            }
            var keys = new long[u];
            Array.Copy(hashes, keys, u);
            return new TranslationMemory(keys, patch.Texts.Select(src, u), new int[u]);
        }

        /// <summary>64비트 키(부호 있는 순서)로 안정 정렬, 값은 따라 움직인다. 8비트씩 8번 도는 기수 정렬.</summary>
        internal static void RadixSort(long[] keys, int[] vals, int n)
        {
            long[] k1 = keys, k2 = new long[n];
            int[] v1 = vals, v2 = new int[n];
            var count = new int[257];
            for (int shift = 0; shift < 64; shift += 8)
            {
                Array.Clear(count, 0, count.Length);
                for (int i = 0; i < n; i++) count[(int)(((ulong)(k1[i] ^ long.MinValue) >> shift) & 0xff) + 1]++;
                if (Array.IndexOf(count, n) >= 0) continue; // 이 자리 숫자가 모두 같으면 건너뛴다
                for (int b = 0; b < 256; b++) count[b + 1] += count[b];
                for (int i = 0; i < n; i++)
                {
                    int d = (int)(((ulong)(k1[i] ^ long.MinValue) >> shift) & 0xff);
                    int at = count[d]++;
                    k2[at] = k1[i];
                    v2[at] = v1[i];
                }
                (k1, k2) = (k2, k1);
                (v1, v2) = (v2, v1);
            }
            if (!ReferenceEquals(k1, keys))
            {
                Array.Copy(k1, keys, n);
                Array.Copy(v1, vals, n);
            }
        }

        internal static bool SameBytes(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            for (int i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
            return true;
        }

        /// <summary>FNV-1a 64 + murmur3 fmix64 (안드로이드와 같은 값)</summary>
        public static long Hash(byte[] bytes) => Hash(bytes, 0, bytes.Length);

        /// <summary>b 의 off 부터 len 바이트</summary>
        public static long Hash(byte[] b, int off, int len)
        {
            unchecked
            {
                ulong h = 0xcbf29ce484222325UL;
                for (int k = off, end = off + len; k < end; k++)
                {
                    h ^= b[k];
                    h *= 0x100000001b3UL;
                }
                h ^= h >> 33; h *= 0xff51afd7ed558ccdUL;
                h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53UL;
                h ^= h >> 33;
                return (long)h;
            }
        }
    }
}
