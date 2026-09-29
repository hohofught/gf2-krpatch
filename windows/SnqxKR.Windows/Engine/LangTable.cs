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
    /// 문장은 UTF-8 바이트 그대로 들고 있다. 빈 문장은 빈 배열이다 (proto3 는 빈 문자열 필드를 생략한다).
    /// </summary>
    public sealed class LangTable
    {
        public long[] Ids { get; }
        public byte[][] Texts { get; }
        public long ChunkSize { get; }
        public long HeaderFlag { get; }
        public int Count => Ids.Length;

        public LangTable(long[] ids, byte[][] texts, long chunkSize = 10, long headerFlag = 1)
        {
            if (ids.Length != texts.Length) throw new ArgumentException("ids/texts 길이가 다릅니다");
            Ids = ids;
            Texts = texts;
            ChunkSize = chunkSize;
            HeaderFlag = headerFlag;
        }

        /// <summary>게임 버전 구분용 지문 (<see cref="LayoutKeyOf"/>)</summary>
        public string LayoutKey() => LayoutKeyOf(Ids);

        /// <summary>Id 오름차순으로 늘어선 위치</summary>
        public int[] SortedOrder()
        {
            var keys = (long[])Ids.Clone();
            var order = Enumerable.Range(0, Count).ToArray();
            Array.Sort(keys, order);
            return order;
        }

        /// <summary>커뮤니티 한패와 같은 모양: Id 오름차순 본문 + 청크 색인(청크 번호 내림차순). 한패를 읽어 다시 쓰면 바이트 단위로 같다.</summary>
        public byte[] ToBytes()
        {
            var body = new MemoryStream();
            var header = HeaderBytes(WriteBody(body));
            var output = new MemoryStream((int)(4 + header.Length + body.Length));
            WriteU32(output, header.Length);
            output.Write(header, 0, header.Length);
            body.Position = 0;
            body.CopyTo(output);
            return output.ToArray();
        }

        /// <summary><see cref="ToBytes"/> 와 같은 내용을 파일로. 본문을 임시 파일로 흘려 써서 메모리를 적게 쓴다.</summary>
        public void WriteTo(string path)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var bodyTmp = path + ".body";
            try
            {
                Chunks chunks;
                using (var fs = new FileStream(bodyTmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                    chunks = WriteBody(fs);
                var header = HeaderBytes(chunks);
                using var output = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16);
                WriteU32(output, header.Length);
                output.Write(header, 0, header.Length);
                using var body = File.OpenRead(bodyTmp);
                body.CopyTo(output, 1 << 16);
            }
            finally
            {
                File.Delete(bodyTmp);
            }
        }

        private sealed class Chunks
        {
            public readonly List<long> Ids = new List<long>();
            public readonly List<long> Offsets = new List<long>();
            public readonly List<long> Sizes = new List<long>();
        }

        /// <summary>Id 오름차순으로 본문을 쓰며 청크(Id/10)마다 offset·size 를 모은다. 같은 청크는 본문에서 연속이다.</summary>
        private Chunks WriteBody(Stream s)
        {
            var c = new Chunks();
            long pos = 0, lastChunk = long.MinValue, chunkStart = 0;
            foreach (var i in SortedOrder())
            {
                long id = Ids[i];
                var text = Texts[i];
                int inner = (id != 0 ? 1 + VarintSize(id) : 0) +
                            (text.Length > 0 ? 1 + VarintSize(text.Length) + text.Length : 0);
                long cid = id / ChunkSize;
                if (cid != lastChunk)
                {
                    if (lastChunk != long.MinValue) c.Sizes.Add(pos - chunkStart);
                    c.Ids.Add(cid);
                    c.Offsets.Add(pos);
                    lastChunk = cid;
                    chunkStart = pos;
                }
                s.WriteByte(0x0a); pos += 1 + PutVarint(s, inner);
                if (id != 0) { s.WriteByte(0x08); pos += 1 + PutVarint(s, id); }
                if (text.Length > 0)
                {
                    s.WriteByte(0x12); pos += 1 + PutVarint(s, text.Length);
                    s.Write(text, 0, text.Length); pos += text.Length;
                }
            }
            if (lastChunk != long.MinValue) c.Sizes.Add(pos - chunkStart);
            s.Flush();
            return c;
        }

        /// <summary>색인: 상수 두 개 + 청크 목록(청크 번호 내림차순, 원본 한패와 같은 순서)</summary>
        private byte[] HeaderBytes(Chunks c)
        {
            var h = new MemoryStream(c.Ids.Count * 16 + 8);
            h.WriteByte(0x08); PutVarint(h, ChunkSize);
            h.WriteByte(0x10); PutVarint(h, HeaderFlag);
            for (int k = c.Ids.Count - 1; k >= 0; k--)
            {
                long cid = c.Ids[k], off = c.Offsets[k], len = c.Sizes[k];
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

        public static LangTable Read(string path) => Parse(File.ReadAllBytes(path));

        public static LangTable Parse(byte[] b)
        {
            long headerLen = b[0] | ((long)b[1] << 8) | ((long)b[2] << 16) | ((long)b[3] << 24);
            int bodyStart = checked(4 + (int)headerLen);
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
                        int skip = (int)Varint(b, ref p);
                        p += skip;
                        break;
                    default: throw new InvalidDataException("색인 wire type " + (tag & 7));
                }
            }

            var ids = new List<long>(1 << 19);
            var texts = new List<byte[]>(1 << 19);
            p = bodyStart;
            while (p < b.Length)
            {
                long tag = Varint(b, ref p);
                if (tag != 0x0a) throw new InvalidDataException($"본문 태그 {tag} @ {p}");
                int len = (int)Varint(b, ref p);
                int end = p + len;
                long id = 0;
                byte[] text = Empty;
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
                            int l = (int)Varint(b, ref p);
                            if (t >> 3 == 2)
                            {
                                text = new byte[l];
                                Buffer.BlockCopy(b, p, text, 0, l);
                            }
                            p += l;
                            break;
                        default: throw new InvalidDataException("본문 wire type " + (t & 7));
                    }
                }
                ids.Add(id);
                texts.Add(text);
                p = end;
            }
            return new LangTable(ids.ToArray(), texts.ToArray(), chunkSize, flag);
        }

        /// <summary>본문의 Id 만 읽는다. 문장은 건너뛰어 메모리를 거의 쓰지 않는다 (버전 지문용).</summary>
        public static long[] ReadIds(string path)
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 16);
            var s = new ProtoReader(fs);
            s.Skip(s.U32());
            var ids = new List<long>(1 << 19);
            while (true)
            {
                var tag = s.VarintOrEof();
                if (tag == null) break;
                if (tag != 0x0a) throw new InvalidDataException("본문 태그 " + tag);
                long len = s.Varint();
                long end = s.Pos + len;
                long id = 0;
                while (s.Pos < end)
                {
                    long t = s.Varint();
                    switch (t & 7)
                    {
                        case 0:
                            long v = s.Varint();
                            if (t >> 3 == 1) id = v;
                            break;
                        case 2:
                            s.Skip(s.Varint());
                            break;
                        default: throw new InvalidDataException("본문 wire type " + (t & 7));
                    }
                }
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
                int len = (int)Varint(header, ref p);
                int end = p + len;
                if (tag >> 3 == 3)
                {
                    long cid = 0;
                    while (p < end)
                    {
                        long t = Varint(header, ref p);
                        if ((t & 7) == 0) { long v = Varint(header, ref p); if (t >> 3 == 1) cid = v; }
                        else { int l = (int)Varint(header, ref p); p += l; }
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

        internal static long Varint(byte[] b, ref int p)
        {
            long r = 0;
            int s = 0;
            while (true)
            {
                int x = b[p++];
                r |= (long)(x & 0x7f) << s;
                if (x < 0x80) return r;
                s += 7;
            }
        }

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

        private static void WriteU32(Stream s, int v)
        {
            s.WriteByte((byte)v); s.WriteByte((byte)(v >> 8)); s.WriteByte((byte)(v >> 16)); s.WriteByte((byte)(v >> 24));
        }

        private static readonly byte[] Empty = new byte[0];

        /// <summary>Stream 위의 protobuf 읽기. 읽은 바이트 수(Pos)를 센다.</summary>
        private sealed class ProtoReader
        {
            private readonly Stream input;
            public long Pos { get; private set; }

            public ProtoReader(Stream input) { this.input = input; }

            private int Byte()
            {
                int x = input.ReadByte();
                if (x < 0) throw new EndOfStreamException();
                Pos++;
                return x;
            }

            public long? VarintOrEof()
            {
                int first = input.ReadByte();
                if (first < 0) return null;
                Pos++;
                long r = first & 0x7f;
                if (first < 0x80) return r;
                int s = 7;
                while (true)
                {
                    int x = Byte();
                    r |= (long)(x & 0x7f) << s;
                    if (x < 0x80) return r;
                    s += 7;
                }
            }

            public long Varint() => VarintOrEof() ?? throw new EndOfStreamException();

            public long U32() => (long)Byte() | ((long)Byte() << 8) | ((long)Byte() << 16) | ((long)Byte() << 24);

            public void Skip(long n)
            {
                if (input.CanSeek) { input.Seek(n, SeekOrigin.Current); Pos += n; return; }
                for (long i = 0; i < n; i++) Byte();
            }
        }
    }
}
