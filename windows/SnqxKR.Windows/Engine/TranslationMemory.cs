using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace SnqxKR.Engine
{
    /// <summary>
    /// 중국어 원문 → 한국어 번역 (안드로이드 TranslationMemory.kt 와 같은 동작).
    /// 게임 업데이트마다 Id 가 전부 다시 매겨지므로 원문으로 찾는다. 원문은 64비트 해시로만 들고 있다 (25만 줄 ≈ 30MB).
    /// </summary>
    public sealed class TranslationMemory
    {
        private readonly long[] keys;     // 오름차순
        private readonly byte[][] values;

        private TranslationMemory(long[] keys, byte[][] values)
        {
            this.keys = keys;
            this.values = values;
        }

        public int Count => keys.Length;

        public byte[]? Get(byte[] source)
        {
            int i = Array.BinarySearch(keys, Hash(source));
            return i >= 0 ? values[i] : null;
        }

        /// <summary>두 메모리를 합친다. 같은 원문이면 newer 의 번역이 이긴다.</summary>
        public TranslationMemory MergedWith(TranslationMemory newer)
        {
            var map = new Dictionary<long, byte[]>(Count + newer.Count);
            for (int i = 0; i < keys.Length; i++) map[keys[i]] = values[i];
            for (int i = 0; i < newer.keys.Length; i++) map[newer.keys[i]] = newer.values[i];
            return FromMap(map);
        }

        public void WriteTo(string path)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            using var w = new BinaryWriter(new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16));
            w.Write(Magic);
            w.Write(keys.Length);
            for (int i = 0; i < keys.Length; i++)
            {
                w.Write(keys[i]);
                w.Write(values[i].Length);
                w.Write(values[i]);
            }
        }

        public static TranslationMemory Read(string path)
        {
            using var r = new BinaryReader(new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16));
            if (r.ReadInt32() != Magic) throw new InvalidDataException("번역 메모리 파일이 아닙니다");
            int n = r.ReadInt32();
            var keys = new long[n];
            var values = new byte[n][];
            for (int i = 0; i < n; i++)
            {
                keys[i] = r.ReadInt64();
                values[i] = r.ReadBytes(r.ReadInt32());
            }
            return new TranslationMemory(keys, values);
        }

        /// <summary>
        /// 같은 게임 버전의 공식 원문과 한패를 같은 Id 끼리 짝지어 만든다.
        /// 한패가 원문을 그대로 둔 문장(미번역, 숫자·기호)은 넣지 않는다.
        /// </summary>
        public static TranslationMemory Build(LangTable official, LangTable patch)
        {
            if (official.LayoutKey() != patch.LayoutKey()) throw new InvalidOperationException("공식 원문과 한패의 게임 버전이 다릅니다");
            var patchOrder = patch.SortedOrder();
            var patchIds = patchOrder.Select(i => patch.Ids[i]).ToArray();
            var map = new Dictionary<long, byte[]>(official.Count);
            for (int i = 0; i < official.Count; i++)
            {
                int j = Array.BinarySearch(patchIds, official.Ids[i]);
                var ko = patch.Texts[patchOrder[j]];
                var zh = official.Texts[i];
                if (SameBytes(ko, zh)) continue;
                long h = Hash(zh);
                if (!map.ContainsKey(h)) map[h] = ko;
            }
            return FromMap(map);
        }

        private static TranslationMemory FromMap(Dictionary<long, byte[]> map)
        {
            var keys = map.Keys.ToArray();
            Array.Sort(keys);
            return new TranslationMemory(keys, keys.Select(k => map[k]).ToArray());
        }

        internal static bool SameBytes(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            for (int i = 0; i < a.Length; i++) if (a[i] != b[i]) return false;
            return true;
        }

        /// <summary>FNV-1a 64 + murmur3 fmix64 (안드로이드와 같은 값)</summary>
        public static long Hash(byte[] bytes)
        {
            unchecked
            {
                ulong h = 0xcbf29ce484222325UL;
                foreach (var x in bytes)
                {
                    h ^= x;
                    h *= 0x100000001b3UL;
                }
                h ^= h >> 33; h *= 0xff51afd7ed558ccdUL;
                h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53UL;
                h ^= h >> 33;
                return (long)h;
            }
        }

        private const int Magic = 0x534e514d; // "SNQM"
    }
}
