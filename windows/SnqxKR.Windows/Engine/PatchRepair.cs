namespace SnqxKR.Engine
{
    /// <summary>
    /// 게임 업데이트로 한패가 맞지 않게 됐을 때, 새 공식 원문을 번역 메모리로 다시 한국어로 만든다.
    /// 메모리에 없는 문장(새 콘텐츠)은 중국어 원문 그대로 둔다. 색인은 LangTable 이 쓸 때 다시 계산한다.
    /// </summary>
    public static class PatchRepair
    {
        public sealed class Result
        {
            public Result(LangTable table, int translated, int leftChinese)
            {
                Table = table;
                Translated = translated;
                LeftChinese = leftChinese;
            }

            public LangTable Table { get; }
            /// <summary>번역 메모리로 한국어가 된 문장</summary>
            public int Translated { get; }
            /// <summary>한자가 있는데 메모리에 없어 중국어로 남은 문장</summary>
            public int LeftChinese { get; }
            public double Coverage => (double)Translated / System.Math.Max(1, Translated + LeftChinese);
        }

        public static Result Repair(LangTable official, TranslationMemory tm)
        {
            int translated = 0, leftChinese = 0;
            var texts = new byte[official.Count][];
            for (int i = 0; i < official.Count; i++)
            {
                var zh = official.Texts[i];
                var ko = tm.Get(zh);
                if (ko != null) { translated++; texts[i] = ko; }
                else
                {
                    if (HasHan(zh)) leftChinese++;
                    texts[i] = zh;
                }
            }
            return new Result(new LangTable(official.Ids, texts, official.ChunkSize, official.HeaderFlag), translated, leftChinese);
        }

        /// <summary>UTF-8 안에 CJK 통합 한자(U+4E00..U+9FFF)가 있는지</summary>
        public static bool HasHan(byte[] utf8)
        {
            for (int i = 0; i + 2 < utf8.Length;)
            {
                int b0 = utf8[i];
                if (b0 >= 0xE4 && b0 <= 0xE9)
                {
                    int cp = ((b0 & 0x0F) << 12) | ((utf8[i + 1] & 0x3F) << 6) | (utf8[i + 2] & 0x3F);
                    if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
                    i += 3;
                }
                else i++;
            }
            return false;
        }
    }
}
