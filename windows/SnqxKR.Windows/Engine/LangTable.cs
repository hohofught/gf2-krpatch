using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace SnqxKR.Engine
{
    /// <summary>
    /// LangPackageTable*.bytes 한 파일. 형식은 docs/lang-table-format.md 참고 (안드로이드 langtable/LangTable.kt 와 같은 동작).
    ///
    ///  [u32 LE 색인 길이][색인 protobuf][본문 protobuf]
    ///  본문: repeated TextMap { int64 Id = 1; string Content = 2 }
    ///  색인: 1 = 청크 크기(10), 2 = 1, 3 = repeated { 청크 번호(Id/10), { 본문 내 offset, size } }
    ///
    /// 문장은 UTF-8 바이트 그대로다. <see cref="Read"/> 는 파일을 한 번에 읽고 문장마다 그 안의 자리만 든다
    /// (<see cref="Engine.Texts"/>). 빈 문장은 길이 0 이다 (proto3 는 빈 문자열 필드를 생략한다).
    /// </summary>
    public sealed class LangTable
    {
        public long[] Ids { get; }
        public Texts Texts { get; }
        public long ChunkSize { get; }
        public long HeaderFlag { get; }
        public int Count => Ids.Length;

        public LangTable(long[] ids, Texts texts, long chunkSize = 10, long headerFlag = 1)
        {
            if (ids.Length != texts.Count) throw new ArgumentException("ids/texts 길이가 다릅니다");
            Ids = ids;
            Texts = texts;
            ChunkSize = chunkSize;
            HeaderFlag = headerFlag;
        }

        /// <summary>바이트 배열들로 (시험·작은 표용)</summary>
        public LangTable(long[] ids, byte[][] texts, long chunkSize = 10, long headerFlag = 1)
            : this(ids, Texts.Of(texts), chunkSize, headerFlag)
        {
        }

        /// <summary>게임 버전 구분용 지문 (<see cref="LayoutKeyOf"/>)</summary>
        public string LayoutKey() => LayoutKeyOf(Ids);

        private int[]? order;

        /// <summary>Id 오름차순으로 늘어선 위치 (처음 한 번만 계산한다. 고치지 말 것)</summary>
        public int[] SortedOrder() => order ??= SortedOrderOf(Ids);

        /// <summary>다른 표와 Id 집합이 같은지 (같은 게임 버전)</summary>
        public bool SameIds(LangTable other)
        {
            if (Count != other.Count) return false;
            var a = SortedOrder();
            var b = other.SortedOrder();
            for (int k = 0; k < a.Length; k++) if (Ids[a[k]] != other.Ids[b[k]]) return false;
            return true;
        }

        /// <summary>
        /// Id 오름차순 위치. 한패 파일은 이미 오름차순이라 그대로 쓰고, 아니면(공식 원문)
        /// Id 와 위치를 long 하나에 담아 정렬한다 (Id &lt; 2^42, 줄 &lt; 2^21 일 때. 아니면 일반 정렬).
        /// </summary>
        internal static int[] SortedOrderOf(long[] ids)
        {
            int n = ids.Length;
            bool sorted = true, packable = n < (1 << 21);
            for (int i = 0; i < n; i++)
            {
                if (i > 0 && ids[i] < ids[i - 1]) sorted = false;
                if (ids[i] < 0 || ids[i] >= (1L << 42)) packable = false;
            }
            var result = new int[n];
            if (sorted)
            {
                for (int i = 0; i < n; i++) result[i] = i;
                return result;
            }
            if (!packable)
            {
                // 같은 Id 는 원래 순서대로 (안정)
                return Enumerable.Range(0, n).OrderBy(i => ids[i]).ToArray();
            }
            var packed = new long[n];
            for (int i = 0; i < n; i++) packed[i] = (ids[i] << 21) | (long)i;
            Array.Sort(packed);
            for (int i = 0; i < n; i++) result[i] = (int)(packed[i] & 0x1FFFFF);
            return result;
        }

        /// <summary>커뮤니티 한패와 같은 모양: Id 오름차순 본문 + 청크 색인(청크 번호 내림차순). 한패를 읽어 다시 쓰면 바이트 단위로 같다.</summary>
        public byte[] ToBytes()
        {
            var plan = MakePlan();
            var output = new MemoryStream((int)(4 + plan.Header.Length + plan.BodySize));
            using (var w = new BlockWriter(output, null)) Write(w, plan);
            return output.ToArray();
        }

        /// <summary>
        /// <see cref="ToBytes"/> 와 같은 내용을 파일로 쓰고 SHA-256(소문자 16진수)을 돌려준다.
        /// 색인을 먼저 계산해서 한 번에 흘려 쓰고(임시 파일 없음), 해시도 쓰면서 같이 구한다 (다시 읽지 않음).
        /// </summary>
        public string WriteTo(string path)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var plan = MakePlan();
            using var sha = SHA256.Create();
            using (var fs = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
            using (var w = new BlockWriter(fs, sha))
                Write(w, plan);
            sha.TransformFinalBlock(new byte[0], 0, 0);
            return Hex(sha.Hash);
        }

        private sealed class Plan
        {
            public Plan(int[] order, byte[] header, long bodySize) { Order = order; Header = header; BodySize = bodySize; }
            public int[] Order { get; }
            public byte[] Header { get; }
            public long BodySize { get; }
        }

        /// <summary>쓰기 전에 줄마다 크기를 계산해 청크(Id/10)마다 offset·size 를 모은다. 같은 청크는 본문에서 연속이다.</summary>
        private Plan MakePlan()
        {
            var order = SortedOrder();
            var cids = new List<long>();
            var offs = new List<long>();
            var lens = new List<long>();
            long pos = 0, lastChunk = long.MinValue, chunkStart = 0;
            foreach (var i in order)
            {
                long id = Ids[i];
                int inner = InnerSize(id, Texts.Length(i));
                long cid = id / ChunkSize;
                if (cid != lastChunk)
                {
                    if (lastChunk != long.MinValue) lens.Add(pos - chunkStart);
                    cids.Add(cid);
                    offs.Add(pos);
                    lastChunk = cid;
                    chunkStart = pos;
                }
                pos += 1 + VarintSize(inner) + inner;
            }
            if (lastChunk != long.MinValue) lens.Add(pos - chunkStart);
            return new Plan(order, HeaderBytes(cids, offs, lens), pos);
        }

        private void Write(BlockWriter w, Plan plan)
        {
            w.U32(plan.Header.Length);
            w.Bytes(plan.Header);
            foreach (var i in plan.Order)
            {
                long id = Ids[i];
                int n = Texts.Length(i);
                w.Byte(0x0a); w.Varint(InnerSize(id, n));
                if (id != 0) { w.Byte(0x08); w.Varint(id); }
                if (n > 0) { w.Byte(0x12); w.Varint(n); w.Bytes(Texts.Buf(i), Texts.Off[i], n); }
            }
        }

        private static int InnerSize(long id, int n) =>
            (id != 0 ? 1 + VarintSize(id) : 0) + (n > 0 ? 1 + VarintSize(n) + n : 0);

        /// <summary>색인: 상수 두 개 + 청크 목록(청크 번호 내림차순, 원본 한패와 같은 순서)</summary>
        private byte[] HeaderBytes(List<long> cids, List<long> offs, List<long> lens)
        {
            var h = new MemoryStream(cids.Count * 16 + 8);
            h.WriteByte(0x08); PutVarint(h, ChunkSize);
            h.WriteByte(0x10); PutVarint(h, HeaderFlag);
            for (int k = cids.Count - 1; k >= 0; k--)
            {
                long cid = cids[k], off = offs[k], len = lens[k];
                int locLen = (off != 0 ? 1 + VarintSize(off) : 0) + (len != 0 ? 1 + VarintSize(len) : 0);
                int entLen = (cid != 0 ? 1 + VarintSize(cid) : 0) + 1 + VarintSize(locLen) + locLen;
                h.WriteByte(0x1a); PutVarint(h, entLen);
                if (cid != 0) { h.WriteByte(0x08); PutVarint(h, cid); }
                h.WriteByte(0x12); PutVarint(h, locLen);
                if (off != 0) { h.WriteByte(0x08); PutVarint(h, off); }
                if (len != 0) { h.WriteByte(0x10); PutVarint(h, len); }
            }
            return h.ToArray();
        }

        /// <summary>파일을 한 번에 읽고 줄마다 Id 와 문장 자리(시작·길이)만 만든다. 문장은 복사하지 않는다.</summary>
        public static LangTable Read(string path) => Parse(File.ReadAllBytes(path));

        public static LangTable Parse(byte[] b)
        {
            long headerLen = b[0] | ((long)b[1] << 8) | ((long)b[2] << 16) | ((long)b[3] << 24);
            if (b.Length < 4 || headerLen > b.Length - 4) throw new InvalidDataException("색인 길이가 파일보다 큼");
            int bodyStart = 4 + (int)headerLen;
            if (bodyStart > b.Length) throw new InvalidDataException("색인 길이가 파일보다 큼");

            // 색인 앞의 상수 두 개만 읽는다 (청크 목록은 쓸 때 다시 계산한다)
            long chunkSize = 10, flag = 1;
            int p = 4;
            while (p < bodyStart)
            {
                long tag = Varint(b, ref p);
                switch (tag & 7)
                {
                    case 0:
                        long v = Varint(b, ref p);
                        if (tag >> 3 == 1) chunkSize = v; else if (tag >> 3 == 2) flag = v;
                        break;
                    case 2:
                        // p += Len(ref p) 로 쓰면 옮기기 전 p 에 더해지므로 나눠 쓴다
                        int skip = Len(b, ref p, bodyStart);
                        p += skip;
                        break;
                    default: throw new InvalidDataException("색인 wire type " + (tag & 7));
                }
            }
            if (p != bodyStart) throw new InvalidDataException("색인이 제 길이를 넘음");

            // 줄 수를 먼저 세어 배열을 딱 맞게 잡는다 (늘리며 복사하지 않게)
            int count = CountEntries(b, bodyStart);
            var ids = new long[count];
            var offs = new int[count];
            var lens = new int[count];
            int row = 0;
            while (p < b.Length)
            {
                long tag = Varint(b, ref p);
                if (tag != 0x0a) throw new InvalidDataException($"본문 태그 {tag} @ {p}");
                int len = Len(b, ref p, b.Length);
                int end = p + len;
                long id = 0;
                int off = 0, n = 0;
                while (p < end)
                {
                    long t = Varint(b, ref p);
                    switch (t & 7)
                    {
                        case 0:
                            long v = Varint(b, ref p);
                            if (t >> 3 == 1) id = v;
                            break;
                        case 2:
                            int l = Len(b, ref p, end);
                            if (t >> 3 == 2) { off = p; n = l; }
                            p += l;
                            break;
                        default: throw new InvalidDataException("본문 wire type " + (t & 7));
                    }
                }
                // 안쪽 필드가 한 줄의 끝을 넘으면 깨진 파일 (Kotlin·C++ 엔진과 같은 규칙)
                if (p != end) throw new InvalidDataException("본문 한 줄이 제 길이를 넘음");
                if (row >= count) throw new InvalidOperationException("줄 수가 미리 센 것보다 많음"); // 미리 센 곳에서 틀이 깨졌으면 위에서 이미 오류가 났다
                ids[row] = id;
                offs[row] = off;
                lens[row] = n;
                row++;
            }
            return new LangTable(ids, new Texts(new[] { b }, null, offs, lens), chunkSize, flag);
        }

        /// <summary>본문 줄 수. 틀(태그·길이)이 깨진 곳에서 멈춘다 — 그 줄은 본 읽기가 같은 검사로 오류를 낸다</summary>
        private static int CountEntries(byte[] b, int start)
        {
            int p = start, n = 0;
            try
            {
                while (p < b.Length)
                {
                    if (Varint(b, ref p) != 0x0a) break;
                    long len = Varint(b, ref p);
                    if (len < 0 || len > b.Length - p) break;
                    p += (int)len;
                    n++;
                }
            }
            catch (IOException)
            {
                // 끊긴 varint: 본 읽기가 같은 자리에서 오류를 낸다
            }
            return n;
        }

        /// <summary>
        /// 본문의 Id 만 읽는다 (버전 지문용). 파일을 한 번에 읽어 배열에서 훑는다.
        /// (Stream.ReadByte·Seek 를 줄마다 부르면 50만 줄에 0.6초 걸렸다)
        /// </summary>
        public static long[] ReadIds(string path)
        {
            byte[] b;
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 16))
            {
                b = new byte[fs.Length];
                int got = 0;
                while (got < b.Length) { int k = fs.Read(b, got, b.Length - got); if (k <= 0) throw new EndOfStreamException(); got += k; }
            }
            long headerLen = b[0] | ((long)b[1] << 8) | ((long)b[2] << 16) | ((long)b[3] << 24);
            if (headerLen > b.Length - 4) throw new InvalidDataException("색인 길이가 파일보다 큼");
            int p = 4 + (int)headerLen;
            var ids = new List<long>(1 << 19);
            while (p < b.Length)
            {
                long tag = Varint(b, ref p);
                if (tag != 0x0a) throw new InvalidDataException("본문 태그 " + tag);
                // p + Varint(ref p) 로 쓰면 p 를 읽은 뒤에 Varint 가 p 를 옮기므로 나눠 쓴다
                int len = Len(b, ref p, b.Length);
                int end = p + len;
                long id = 0;
                while (p < end)
                {
                    long t = Varint(b, ref p);
                    switch (t & 7)
                    {
                        case 0:
                            long v = Varint(b, ref p);
                            if (t >> 3 == 1) id = v;
                            break;
                        case 2:
                            int l = Len(b, ref p, end);
                            p += l;
                            break;
                        default: throw new InvalidDataException("본문 wire type " + (t & 7));
                    }
                }
                if (p != end) throw new InvalidDataException("본문 한 줄이 제 길이를 넘음");
                ids.Add(id);
            }
            return ids.ToArray();
        }

        /// <summary>
        /// 게임 버전 구분용 지문. Id 는 업데이트마다 전부 다시 매겨지므로 Id 집합이 같으면 같은 버전의 표다.
        /// 공식 원문과 그 버전용 한패는 지문이 같다.
        /// </summary>
        public static string LayoutKeyOf(long[] ids)
        {
            var sorted = (long[])ids.Clone();
            Array.Sort(sorted);
            return sorted.Length + ":" + Digest16(sorted);
        }

        /// <summary>색인(파일 앞 1MB 안쪽)만으로 구하는 버전 지문: 청크 번호(Id/10) 집합</summary>
        public static string ChunkKeyOf(byte[] header)
        {
            var cids = new List<long>();
            int p = 0;
            while (p < header.Length)
            {
                long tag = Varint(header, ref p);
                if ((tag & 7) == 0) { Varint(header, ref p); continue; }
                int len = Len(header, ref p, header.Length);
                int end = p + len;
                if (tag >> 3 == 3)
                {
                    long cid = 0;
                    while (p < end)
                    {
                        long t = Varint(header, ref p);
                        if ((t & 7) == 0) { long v = Varint(header, ref p); if (t >> 3 == 1) cid = v; }
                        else { int l = Len(header, ref p, end); p += l; }
                    }
                    cids.Add(cid);
                }
                p = end;
            }
            var sorted = cids.ToArray();
            Array.Sort(sorted);
            return sorted.Length + ":" + Digest16(sorted);
        }

        /// <summary>
        /// 본문 앞 maxBytes 안의 한글 음절 수. 공식 중섭 파일은 0 이고 한패는 대부분 한글이라 둘을 가른다. 읽을 수 없으면 -1.
        /// </summary>
        public static int HangulCount(string path, int maxBytes)
        {
            try
            {
                using var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                var head = new byte[4];
                if (f.Read(head, 0, 4) != 4) return -1;
                long start = 4L + (head[0] | ((long)head[1] << 8) | ((long)head[2] << 16) | ((long)head[3] << 24));
                if (start >= f.Length) return -1;
                f.Seek(start, SeekOrigin.Begin);
                var buf = new byte[(int)Math.Min(maxBytes, f.Length - start)];
                int got = 0;
                while (got < buf.Length) { int k = f.Read(buf, got, buf.Length - got); if (k <= 0) break; got += k; }
                // 한글 음절 U+AC00..U+D7A3 은 UTF-8 로 EA B0 80 ~ ED 9E A3
                int n = 0;
                for (int i = 0; i + 2 < got;)
                {
                    int b0 = buf[i], b1 = buf[i + 1], b2 = buf[i + 2];
                    if (b0 >= 0xEA && b0 <= 0xED && (b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80)
                    {
                        int cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                        if (cp >= 0xAC00 && cp <= 0xD7A3) { n++; i += 3; continue; }
                    }
                    i++;
                }
                return n;
            }
            catch
            {
                return -1;
            }
        }

        private static string Digest16(long[] sorted)
        {
            using var sha = SHA256.Create();
            var buf = new byte[sorted.Length * 8];
            for (int k = 0; k < sorted.Length; k++)
            {
                long v = sorted[k];
                for (int i = 0; i < 8; i++) buf[k * 8 + i] = (byte)(v >> (8 * i));
            }
            var sb = new StringBuilder(16);
            foreach (var x in sha.ComputeHash(buf)) sb.Append(x.ToString("x2"));
            return sb.ToString(0, 16);
        }

        /// <summary>
        /// 길이 varint 를 읽고, 음수이거나 limit 까지 남은 것보다 크면 깨진 파일로 본다
        /// (그대로 쓰면 위치가 뒤로 가 끝없이 돌거나 큰 배열을 잡는다)
        /// </summary>
        private static int Len(byte[] b, ref int p, int limit)
        {
            long v = Varint(b, ref p);
            if (v < 0 || v > limit - p) throw new InvalidDataException("파일이 끊김 (길이 " + v + ")");
            return (int)v;
        }

        /// <summary>protobuf varint. 10바이트를 넘으면 깨진 파일 (protobuf 규칙, Kotlin·C++ 과 같다)</summary>
        internal static long Varint(byte[] b, ref int p)
        {
            long r = 0;
            int s = 0;
            while (true)
            {
                if (p >= b.Length) throw new EndOfStreamException("파일이 중간에 끊김");
                int x = b[p++];
                r |= (long)(x & 0x7f) << s;
                if (x < 0x80) return r;
                s += 7;
                if (s > 63) throw new InvalidDataException("varint 가 너무 김");
            }
        }

        internal static string Hex(byte[] bytes)
        {
            var c = new char[bytes.Length * 2];
            for (int i = 0; i < bytes.Length; i++)
            {
                c[2 * i] = HexDigits[bytes[i] >> 4];
                c[2 * i + 1] = HexDigits[bytes[i] & 15];
            }
            return new string(c);
        }

        private const string HexDigits = "0123456789abcdef";

        private static int PutVarint(Stream s, long value)
        {
            ulong v = (ulong)value;
            int n = 1;
            while (v >= 0x80)
            {
                s.WriteByte((byte)(v | 0x80));
                v >>= 7;
                n++;
            }
            s.WriteByte((byte)v);
            return n;
        }

        private static int VarintSize(long value)
        {
            ulong v = (ulong)value;
            int n = 1;
            while (v >= 0x80) { n++; v >>= 7; }
            return n;
        }


        /// <summary>64KB 블록 단위로 모아 쓰고, 해시가 있으면 블록마다 같이 갱신한다 (Stream.WriteByte 를 바이트마다 부르지 않는다)</summary>
        private sealed class BlockWriter : IDisposable
        {
            private readonly Stream output;
            private readonly HashAlgorithm? hash;
            private readonly byte[] buf = new byte[1 << 16];
            private int n;

            public BlockWriter(Stream output, HashAlgorithm? hash)
            {
                this.output = output;
                this.hash = hash;
            }

            private void FlushBlock()
            {
                if (n == 0) return;
                output.Write(buf, 0, n);
                hash?.TransformBlock(buf, 0, n, null, 0);
                n = 0;
            }

            public void Byte(int b)
            {
                if (n == buf.Length) FlushBlock();
                buf[n++] = (byte)b;
            }

            public void Varint(long value)
            {
                ulong v = (ulong)value;
                while (v >= 0x80)
                {
                    Byte((int)(v | 0x80) & 0xff);
                    v >>= 7;
                }
                Byte((int)v);
            }

            public void U32(int v) { Byte(v & 0xff); Byte((v >> 8) & 0xff); Byte((v >> 16) & 0xff); Byte((v >> 24) & 0xff); }

            public void Bytes(byte[] b) => Bytes(b, 0, b.Length);

            public void Bytes(byte[] b, int off, int len)
            {
                int end = off + len;
                while (off < end)
                {
                    if (n == buf.Length) FlushBlock();
                    int k = Math.Min(end - off, buf.Length - n);
                    Buffer.BlockCopy(b, off, buf, n, k);
                    n += k;
                    off += k;
                }
            }

            public void Dispose()
            {
                FlushBlock();
                output.Flush();
            }
        }
    }
}
