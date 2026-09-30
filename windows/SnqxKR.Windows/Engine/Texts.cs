using System;
using System.Collections.Generic;

namespace SnqxKR.Engine
{
    /// <summary>
    /// 문장 목록. 줄마다 복사하지 않고 파일 내용(바이트 배열) 안의 자리만 든다 (안드로이드 Texts.kt 와 같은 구조).
    /// 줄 i 는 Bufs[Src[i]] 의 Off[i] 부터 Len[i] 바이트다 (Src 가 null 이면 모두 Bufs[0]).
    ///
    /// 윈도우는 파일을 매핑하지 않고 한 번에 읽는다. 매핑해 둔 파일은 다른 프로그램(게임 업데이트)이 줄이거나 바꾸지 못하고,
    /// PC 는 메모리가 넉넉하며, 배열에서 바로 읽는 것이 C# 에서 가장 빠르다.
    /// </summary>
    public sealed class Texts
    {
        internal readonly byte[][] Bufs;
        internal readonly byte[]? Src;
        internal readonly int[] Off;
        internal readonly int[] Len;

        internal Texts(byte[][] bufs, byte[]? src, int[] off, int[] len)
        {
            if (off.Length != len.Length || (src != null && src.Length != off.Length)) throw new ArgumentException("자리 배열 길이가 다릅니다");
            if (bufs.Length > byte.MaxValue) throw new ArgumentException("버퍼가 너무 많음");
            Bufs = bufs;
            Src = src;
            Off = off;
            Len = len;
        }

        public int Count => Off.Length;

        public int Length(int i) => Len[i];

        /// <summary>줄 i 가 든 배열 (Off[i] 부터 Len[i] 바이트)</summary>
        internal byte[] Buf(int i) => Bufs[Src == null ? 0 : Src[i]];

        /// <summary>줄 i 의 복사본 (시험·작은 곳용. 엔진은 Buf·Off·Len 으로 바로 읽는다)</summary>
        public byte[] this[int i]
        {
            get
            {
                var r = new byte[Len[i]];
                Buffer.BlockCopy(Buf(i), Off[i], r, 0, Len[i]);
                return r;
            }
        }

        /// <summary>a 의 줄 i 와 b 의 줄 j 가 바이트 단위로 같은지</summary>
        internal static bool Same(Texts a, int i, Texts b, int j)
        {
            int n = a.Len[i];
            if (n != b.Len[j]) return false;
            byte[] x = a.Buf(i), y = b.Buf(j);
            int p = a.Off[i], q = b.Off[j];
            for (int k = 0; k < n; k++) if (x[p + k] != y[q + k]) return false;
            return true;
        }

        /// <summary>고른 줄만 (배열은 같이 쓴다)</summary>
        internal Texts Select(int[] rows, int n)
        {
            byte[]? src = null;
            if (Src != null) { src = new byte[n]; for (int k = 0; k < n; k++) src[k] = Src[rows[k]]; }
            var off = new int[n];
            var len = new int[n];
            for (int k = 0; k < n; k++) { off[k] = Off[rows[k]]; len[k] = Len[rows[k]]; }
            return new Texts(Bufs, src, off, len);
        }

        /// <summary>바이트 배열들로 (시험·작은 표용). 배열 하나에 이어 붙인다.</summary>
        public static Texts Of(byte[][] arrays)
        {
            long total = 0;
            foreach (var a in arrays) total += a.Length;
            if (total > int.MaxValue) throw new ArgumentException("문장이 너무 큼");
            var all = new byte[total];
            var off = new int[arrays.Length];
            var len = new int[arrays.Length];
            int p = 0;
            for (int i = 0; i < arrays.Length; i++)
            {
                Buffer.BlockCopy(arrays[i], 0, all, p, arrays[i].Length);
                off[i] = p;
                len[i] = arrays[i].Length;
                p += arrays[i].Length;
            }
            return new Texts(new[] { all }, null, off, len);
        }

        /// <summary>
        /// 여러 목록에서 줄을 골라 만드는 새 목록 (임시 복구 결과: 원문·번역 메모리·옛 한패의 줄이 섞인다).
        /// parts 의 배열을 이어 붙여 같이 쓰고, 줄마다 (배열 번호, 시작, 길이)만 적는다.
        /// </summary>
        internal sealed class Builder
        {
            private readonly int[] partBase;
            private readonly Texts view;

            public Builder(IList<Texts> parts, int n)
            {
                partBase = new int[parts.Count];
                var all = new List<byte[]>();
                for (int k = 0; k < parts.Count; k++) { partBase[k] = all.Count; all.AddRange(parts[k].Bufs); }
                view = new Texts(all.ToArray(), new byte[n], new int[n], new int[n]);
            }

            /// <summary>지금까지 채운 결과 (build 전에도 읽을 수 있다)</summary>
            public Texts View => view;

            /// <summary>결과 줄 i = parts[part] 의 줄 j</summary>
            public void Set(int i, int part, Texts from, int j)
            {
                view.Src![i] = (byte)(partBase[part] + (from.Src == null ? 0 : from.Src[j]));
                view.Off[i] = from.Off[j];
                view.Len[i] = from.Len[j];
            }

            /// <summary>결과 줄 i 가 parts[part] 에서 왔는지</summary>
            public bool IsFrom(int i, int part, Texts from)
            {
                int b = view.Src![i] - partBase[part];
                return b >= 0 && b < from.Bufs.Length;
            }

            /// <summary>앞 count 줄로 끝낸다 (이후 Set 하지 않는다)</summary>
            public Texts Build(int count)
            {
                if (count == view.Count) return view;
                var src = new byte[count];
                var off = new int[count];
                var len = new int[count];
                Array.Copy(view.Src!, src, count);
                Array.Copy(view.Off, off, count);
                Array.Copy(view.Len, len, count);
                return new Texts(view.Bufs, src, off, len);
            }
        }
    }
}
