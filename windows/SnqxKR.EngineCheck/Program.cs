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

        // 4) 번역 메모리 저장·읽기
        var tmFile = Path.Combine(Path.GetTempPath(), "snqx-tm.bin");
        tm.WriteTo(tmFile);
        var back = TranslationMemory.Read(tmFile);
        File.Delete(tmFile);
        var probe = official.Texts.First(t => tm.Get(t) != null);
        Check("번역 메모리 저장 후 읽어도 같음", back.Count == tm.Count && back.Get(probe)!.SequenceEqual(tm.Get(probe)!));

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
