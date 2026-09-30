using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using SnqxKR.Engine;

// 사용법: SnqxKR.EngineCheck <샘플 폴더>
// 안드로이드 LangTableTest 와 같은 파일·시나리오로 C# 엔진 결과를 찍는다. 실패하면 종료 코드 1.
internal static class Program
{
    private static int failures;

    private static int Main(string[] args)
    {
        var dir = args.Length > 0 ? args[0] : ".";
        string F(string name) => Path.Combine(dir, name);

        // 1) 버전별 한패: 파싱 → 재작성 바이트 일치, 파일로 흘려 써도 일치
        var patches = new[] { "patch-v6-0722.bytes", "patch-0804.bytes", "patch-0827.bytes", "patch-v3-0901.bytes", "patch-v2-0919.bytes", "patch-current.bytes" };
        foreach (var name in patches.Where(n => File.Exists(F(n))))
        {
            var raw = File.ReadAllBytes(F(name));
            var table = LangTable.Parse(raw);
            Check($"{name} 재작성 일치 ({table.Count:N0}줄)", raw.SequenceEqual(table.ToBytes()));
        }
        var tmpOut = Path.Combine(Path.GetTempPath(), "snqx-check.bytes");
        LangTable.Read(F("patch-current.bytes")).WriteTo(tmpOut);
        Check("파일로 흘려 쓴 결과 일치", File.ReadAllBytes(tmpOut).SequenceEqual(File.ReadAllBytes(F("patch-current.bytes"))));
        File.Delete(tmpOut);

        // 2) 버전 지문: 안드로이드(폰)에서 계산한 값과 같아야 한다
        var layout = LangTable.LayoutKeyOf(LangTable.ReadIds(F("patch-current.bytes")));
        Check($"현재 한패 버전 지문 {layout} (폰: 497166:a6506173cdddd936)", layout == "497166:a6506173cdddd936");
        Check("PC 공식 원본과 현재 한패 버전 같음", LangTable.LayoutKeyOf(LangTable.ReadIds(F("official-pc-current.bytes"))) == layout);
        Check("색인 지문: 08-24 원본 = 08-27 한패", ChunkKey(F("official-0824.bytes")) == ChunkKey(F("patch-0827.bytes")));
        Check("색인 지문: 08-24 원본 ≠ 현재 한패", ChunkKey(F("official-0824.bytes")) != ChunkKey(F("patch-current.bytes")));
        Check("한글 수: 공식 원본 0", LangTable.HangulCount(F("official-pc-current.bytes"), 2 << 20) == 0);
        Check("한글 수: 한패 > 0", LangTable.HangulCount(F("patch-current.bytes"), 2 << 20) > 0);

        // 3) 실전: 08월(V4) 번역 메모리로 세 번 업데이트된 현재(V1) 원본 복구 → 안드로이드 결과와 같아야 한다
        var tm = TranslationMemory.Build(LangTable.Read(F("official-0824.bytes")), LangTable.Read(F("patch-0827.bytes")));
        var official = LangTable.Read(F("official-pc-current.bytes"));
        var result = PatchRepair.Repair(official, tm);
        Console.WriteLine($"  번역 메모리 {tm.Count:N0}줄 → 한국어 {result.Translated:N0}줄 ({result.Coverage:P1}), 중국어로 남음 {result.LeftChinese:N0}줄");
        Check("안드로이드와 같은 결과 (TM 252,684 / 복구 463,540 / 남음 30,599)",
            tm.Count == 252684 && result.Translated == 463540 && result.LeftChinese == 30599);

        // 4) 옛 한패(V2)의 번역을 새 버전(V1) 자리로 옮기기 → 안드로이드 결과와 같아야 한다
        if (File.Exists(F("patch-v2-0919.bytes")))
        {
            var truth = LangTable.Read(F("patch-current.bytes"));
            var truthById = new Dictionary<long, byte[]>(truth.Count);
            for (int i = 0; i < truth.Count; i++) truthById[truth.Ids[i]] = truth.Texts[i];
            var both = PatchRepair.Repair(official, tm, LangTable.Read(F("patch-v2-0919.bytes")));
            // 복구본은 공식 원본과 같은 순서. 실제 V1 한패와는 Id 로 맞춰 비교한다 (한패가 원문 그대로 둔 줄은 뺌)
            int Same(LangTable t) => Enumerable.Range(0, t.Count).Count(i =>
                truthById.TryGetValue(official.Ids[i], out var want) &&
                !TranslationMemory.SameBytes(want, official.Texts[i]) && TranslationMemory.SameBytes(t.Texts[i], want));
            Console.WriteLine($"  옛 한패에서 옮김 {both.FromPatch:N0}줄, V1 한패와 같은 줄 {Same(result.Table):N0} → {Same(both.Table):N0}");
            Check("안드로이드와 같은 결과 (옮김 5,114 / 한국어 463,799 / V1 한패와 같음 449,602)",
                both.FromPatch == 5114 && both.Translated == 463799 && Same(both.Table) == 449602);
        }

        // 5) 번역 메모리 저장·읽기 (안드로이드와 같은 빅엔디안 v2), 0.2 윈도우판 리틀엔디안 v1 읽기
        var tmFile = Path.Combine(Path.GetTempPath(), "snqx-tm.bin");
        tm.WriteTo(tmFile);
        var head = File.ReadAllBytes(tmFile).Take(4).ToArray();
        var back = TranslationMemory.Read(tmFile);
        var probe = official.Texts[Enumerable.Range(0, official.Count).First(i => tm.Get(official.Texts[i]) != null)];
        Check("번역 메모리 저장 후 읽어도 같음", back.Count == tm.Count && back.Get(probe)!.SequenceEqual(tm.Get(probe)!));
        Check("파일 크기 = ByteSize, 머리 \"SNQ2\"", new FileInfo(tmFile).Length == tm.ByteSize && head.SequenceEqual(new byte[] { 0x53, 0x4e, 0x51, 0x32 }));
        var fake = System.Text.Encoding.UTF8.GetBytes("엔진 점검 전용 원문");
        var ga = new byte[] { 0xea, 0xb0, 0x80 }; // "가"
        using (var w = new BinaryWriter(File.Create(tmFile)))
        {
            w.Write(0x534e514d); w.Write(1); w.Write(TranslationMemory.Hash(fake)); w.Write(ga.Length); w.Write(ga);
        }
        var v1 = TranslationMemory.Read(tmFile);
        File.Delete(tmFile);
        Check("0.2 윈도우판 메모리 파일도 읽음", v1.Count == 1 && v1.Get(fake)!.SequenceEqual(ga));

        // 6) 최대 크기: 새 세대(나중에 합친 한패)가 남고 오래된 세대부터 빠진다
        var merged = v1.MergedWith(tm);
        var capped = merged.Capped(tm.ByteSize);
        Check($"최대 크기 넘으면 오래된 줄부터 뺌 ({merged.Count:N0} → {capped.Count:N0}줄)",
            merged.Count == tm.Count + 1 && capped.Count == tm.Count && capped.Get(fake) == null &&
            capped.Get(probe)!.SequenceEqual(tm.Get(probe)!));

        // 7) 쓰면서 구한 SHA-256 이 파일을 다시 읽어 구한 값과 같다
        var outFile = Path.Combine(Path.GetTempPath(), "snqx-check-sha.bytes");
        var written = result.Table.WriteTo(outFile);
        string reread;
        using (var sha = System.Security.Cryptography.SHA256.Create())
        using (var fs = File.OpenRead(outFile))
            reread = string.Concat(sha.ComputeHash(fs).Select(b => b.ToString("x2")));
        File.Delete(outFile);
        Check("쓰면서 구한 SHA-256 = 다시 읽은 SHA-256", written == reread);

        // 8) 자리 검사: 업데이트 직후 올라온 깨진 한패를 가려낸다 (샘플 폴더의 history/ 가 있을 때)
        var v4 = LangTable.Read(F("official-0824.bytes"));
        foreach (var (off, name, expected) in new[]
                 {
                     (official, "history/20260922-0725-0cce1eb.bytes", false),
                     (official, "history/20260927-1419-76f18c5.bytes", true),
                     (v4, "history/20260811-1111-0398002.bytes", false),
                     (v4, "history/20260827-0119-810ed14.bytes", true),
                 })
        {
            if (!File.Exists(F(name))) continue;
            var a = PatchRepair.Align(off, LangTable.Read(F(name)));
            Check($"자리 검사 {name}: {a.Matching}/{a.Untranslated} ({a.Ratio:P1}) → {(expected ? "정상" : "깨짐")}", a.Ok == expected);
        }

        Console.WriteLine(failures == 0 ? "모두 통과" : $"실패 {failures}개");
        return failures == 0 ? 0 : 1;
    }

    private static string ChunkKey(string path)
    {
        using var f = File.OpenRead(path);
        var len = new byte[4];
        f.Read(len, 0, 4);
        var header = new byte[BitConverter.ToInt32(len, 0)];
        int got = 0;
        while (got < header.Length) got += f.Read(header, got, header.Length - got);
        return LangTable.ChunkKeyOf(header);
    }

    private static void Check(string what, bool ok)
    {
        Console.WriteLine((ok ? "  [통과] " : "  [실패] ") + what);
        if (!ok) failures++;
    }
}
