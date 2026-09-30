using System;
using System.IO;

namespace SnqxKR.Engine
{
    /// <summary>번역 메모리 만들기 결과</summary>
    public sealed class MemoryBuild
    {
        public MemoryBuild(bool built, int size, int dropped, PatchRepair.Alignment alignment)
        {
            Built = built;
            Size = size;
            Dropped = dropped;
            Alignment = alignment;
        }

        /// <summary>false 면 한패가 공식 원문과 자리가 맞지 않아 만들지 않았다 (메모리를 더럽히지 않음)</summary>
        public bool Built { get; }
        public int Size { get; }
        /// <summary>최대 크기를 넘어 뺀 줄</summary>
        public int Dropped { get; }
        public PatchRepair.Alignment Alignment { get; }
    }

    /// <summary>임시 복구 결과</summary>
    public sealed class RepairOutput
    {
        public RepairOutput(int translated, int leftChinese, int fromPatch, string sha256)
        {
            Translated = translated;
            LeftChinese = leftChinese;
            FromPatch = fromPatch;
            Sha256 = sha256;
        }

        public int Translated { get; }
        public int LeftChinese { get; }
        public int FromPatch { get; }
        public string Sha256 { get; }
        public double Coverage => (double)Translated / Math.Max(1, Translated + LeftChinese);
    }

    /// <summary>
    /// 파일 단위 번역 엔진. C# 엔진(<see cref="ManagedEngine"/>)과 C++ 네이티브 엔진(SnqxKR-native.exe 에만 들어 있음)이
    /// 같은 결과를 낸다 (SnqxKR.EngineCheck, native/cli.cpp 로 확인).
    /// </summary>
    public interface IEngine
    {
        /// <summary>화면·로그에 보이는 이름</summary>
        string Name { get; }
        /// <summary>게임 버전 지문 (Id 집합)</summary>
        string LayoutKey(string path);
        PatchRepair.Alignment Alignment(string official, string patch);
        /// <summary>같은 버전 공식 원문 + 한패로 번역 메모리를 만들어 memoryIn 과 합치고 잘라 memoryOut 에 쓴다. 자리가 안 맞으면 만들지 않는다.</summary>
        MemoryBuild BuildMemory(string official, string patch, string? memoryIn, string memoryOut, long maxBytes);
        /// <summary>새 공식 원문을 번역 메모리로 한국어화하고 oldPatch 의 번역을 새 자리로 옮겨 outPath 에 쓴다</summary>
        RepairOutput Repair(string official, string memory, string? oldPatch, string outPath);
    }

    /// <summary>C# 엔진</summary>
    public sealed class ManagedEngine : IEngine
    {
        public string Name => "C#";

        public string LayoutKey(string path) => LangTable.LayoutKeyOf(LangTable.ReadIds(path));

        public PatchRepair.Alignment Alignment(string official, string patch) =>
            PatchRepair.Align(LangTable.Read(official), LangTable.Read(patch));

        public MemoryBuild BuildMemory(string official, string patch, string? memoryIn, string memoryOut, long maxBytes)
        {
            var o = LangTable.Read(official);
            var p = LangTable.Read(patch);
            var a = PatchRepair.Align(o, p);
            if (a.Ok == false) return new MemoryBuild(false, 0, 0, a);
            var fresh = TranslationMemory.Build(o, p);
            TranslationMemory? old = null;
            if (memoryIn != null && File.Exists(memoryIn))
            {
                try { old = TranslationMemory.Read(memoryIn); } catch { old = null; }
            }
            var full = old?.MergedWith(fresh) ?? fresh;
            var cut = full.Capped(maxBytes);
            cut.WriteTo(memoryOut);
            return new MemoryBuild(true, cut.Count, full.Count - cut.Count, a);
        }

        public RepairOutput Repair(string official, string memory, string? oldPatch, string outPath)
        {
            var o = LangTable.Read(official);
            var tm = TranslationMemory.Read(memory);
            var r = PatchRepair.Repair(o, tm, oldPatch != null ? LangTable.Read(oldPatch) : null);
            var sha = r.Table.WriteTo(outPath);
            return new RepairOutput(r.Translated, r.LeftChinese, r.FromPatch, sha);
        }
    }

    /// <summary>지금 쓰는 엔진. 네이티브 판(SnqxKR-native.exe)은 C++ 엔진을 올려 보고, 안 되면 C# 엔진을 쓴다.</summary>
    public static class Engines
    {
        private static IEngine? current;

        /// <summary>네이티브 엔진을 못 올린 이유 (C# 판이거나 성공하면 null)</summary>
        public static string? FallbackReason { get; private set; }

        public static IEngine Current => current ??= Pick();

        private static IEngine Pick()
        {
#if NATIVE
            try
            {
                return NativeEngine.Load();
            }
            catch (Exception e)
            {
                FallbackReason = e.Message;
            }
#endif
            return new ManagedEngine();
        }
    }
}
