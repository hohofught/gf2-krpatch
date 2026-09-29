using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using SnqxKR.Engine;

namespace SnqxKR
{
    public enum PatchStatus
    {
        Unknown,
        NotPatched,     // 대상 파일 없음 (게임을 한 번 실행해 데이터를 받아야 함)
        Official,       // 게임 원본(중국어). 한패 미적용이거나 게임 업데이트로 덮임
        PatchedLatest,  // 최신 한패 적용됨
        PatchedOld,     // 이 앱이 넣은 이전 한패
        Repaired,       // 번역 메모리로 만든 임시 복구본
        Foreign,        // 한패이긴 한데 최신인지 모르거나 다른 버전
    }

    public sealed record Inspection(bool Exists, string Sha, long Size, PatchStatus Status, string GameLayout, bool Running);

    public enum Outcome { Done, Same, GameRunning, NoPatch, VersionMismatch, NoOfficial, NoMemory, NoBackup, OldVersion, Failed }

    public sealed record ActionResult(Outcome Outcome, string Message = "", double Coverage = -1);

    /// <summary>
    /// 한패 적용·임시 복구·원본 복원 (안드로이드 PatchEngine 과 같은 규칙).
    ///  - 게임 폴더에 공식 원본이 보이면 보관하고, 같은 버전 한패가 있으면 번역 메모리에 넣는다
    ///  - 한패와 게임의 버전 지문(Id 집합)이 다르면 옛 한패 적용을 막고 임시 복구를 쓴다
    ///  - 원본 복원은 백업이 지금 게임 버전일 때만 한다
    /// 중섭(CnServerCheck 통과) 설치에만 쓴다. 쓰기 권한이 없으면(Program Files 의 B服 등) 그 파일 복사만 관리자 권한으로 한다.
    /// </summary>
    public sealed class PatchService
    {
        private const int HangulSampleBytes = 2 * 1024 * 1024;
        private static readonly SemaphoreSlim Gate = new SemaphoreSlim(1, 1);

        private readonly Store store;
        private readonly Action<string> log;

        public PatchService(Store store, Action<string> log)
        {
            this.store = store;
            this.log = log;
        }

        public Task<Inspection> InspectAsync(GameInstall g) => Locked(() => Inspect(g));
        public Task<ActionResult> ApplyAsync(GameInstall g) => Locked(() => Apply(g));
        public Task<ActionResult> RepairAsync(GameInstall g) => Locked(() => Repair(g));
        public Task<ActionResult> RestoreAsync(GameInstall g) => Locked(() => Restore(g));
        public Task<string> PatchLayoutAsync() => Locked(PatchLayout);

        public bool CanRepair(GameInstall g, Inspection info)
        {
            var t = store.For(g.GameDir);
            return info.GameLayout.Length > 0 && File.Exists(t.OfficialFile) &&
                   t.OfficialLayout == info.GameLayout && File.Exists(store.MemoryFile);
        }

        private static async Task<T> Locked<T>(Func<T> work)
        {
            await Gate.WaitAsync().ConfigureAwait(false);
            try { return await Task.Run(work).ConfigureAwait(false); }
            finally { Gate.Release(); }
        }

        private Inspection Inspect(GameInstall g)
        {
            if (!g.IsCn) throw new InvalidOperationException("중섭이 아닌 설치");
            var t = store.For(g.GameDir);
            var path = g.TargetFile;
            bool running = GameLocator.IsGameRunningAt(g.GameDir);
            if (!File.Exists(path)) return new Inspection(false, "", -1, PatchStatus.NotPatched, "", running);

            // 크기·수정시각이 지난번과 같으면 해시와 한글 검사를 다시 하지 않는다
            var fi = new FileInfo(path);
            var stamp = fi.Length + ":" + fi.LastWriteTimeUtc.Ticks;
            var seen = t.Seen.Split('|');
            bool same = seen.Length == 3 && seen[0] == stamp;
            var sha = same ? seen[1] : PatchSource.Sha256(path);
            int? hangul = same && int.TryParse(seen[2], out var hc) && hc != -2 ? hc : (int?)null;
            int Hangul() => hangul ??= LangTable.HangulCount(path, HangulSampleBytes);

            PatchStatus status;
            if (sha == store.CachedSha) status = PatchStatus.PatchedLatest;
            else if (sha == t.RepairedSha) status = PatchStatus.Repaired;
            else if (sha == t.AppliedSha) status = PatchStatus.PatchedOld;
            else if (Hangul() == 0) status = PatchStatus.Official;
            else status = PatchStatus.Foreign;
            t.Seen = stamp + "|" + sha + "|" + (hangul ?? -2);

            if (status == PatchStatus.Official && t.OfficialSha != sha) CaptureOfficial(g, path, sha);
            var layout = store.LayoutFor(sha);
            if (layout == null)
            {
                layout = status == PatchStatus.PatchedLatest ? PatchLayout() : LangTable.LayoutKeyOf(LangTable.ReadIds(path));
                if (layout.Length > 0) store.PutLayout(sha, layout);
            }
            return new Inspection(true, sha, fi.Length, status, layout, running);
        }

        /// <summary>게임 폴더의 공식 원본을 앱 데이터로 가져와 둔다. 번역 메모리를 만들고, 업데이트 후 복구할 때 쓴다.</summary>
        private void CaptureOfficial(GameInstall g, string path, string sha)
        {
            var t = store.For(g.GameDir);
            Directory.CreateDirectory(Path.GetDirectoryName(t.OfficialFile)!);
            File.Copy(path, t.OfficialFile, true);
            if (PatchSource.Sha256(t.OfficialFile) != sha) { log("공식 원본 보관 검증 실패"); return; }
            var layout = LangTable.LayoutKeyOf(LangTable.ReadIds(t.OfficialFile));
            t.OfficialSha = sha;
            t.OfficialLayout = layout;
            store.PutLayout(sha, layout);
            log("게임 공식 원본 보관 (버전 지문 " + Short(layout) + ")");
            // 같은 버전 한패를 이미 받아 뒀으면 바로 번역 메모리에 넣는다
            if (PatchLayout() == layout) BuildMemory(t.OfficialFile, store.CacheFile, store.CachedSha);
        }

        private string PatchLayout()
        {
            var sha = store.CachedSha;
            if (sha.Length == 0 || !File.Exists(store.CacheFile)) return "";
            var known = store.LayoutFor(sha);
            if (known != null) return known;
            var layout = LangTable.LayoutKeyOf(LangTable.ReadIds(store.CacheFile));
            store.PutLayout(sha, layout);
            return layout;
        }

        /// <summary>같은 버전의 공식 원본 + 한패로 번역 메모리를 만들어 기존 것과 합친다 (새 번역이 이긴다)</summary>
        private void BuildMemory(string officialFile, string patchFile, string patchSha)
        {
            if (store.MemoryPatchSha == patchSha && File.Exists(store.MemoryFile)) return;
            var fresh = TranslationMemory.Build(LangTable.Read(officialFile), LangTable.Read(patchFile));
            TranslationMemory? old = null;
            if (File.Exists(store.MemoryFile))
            {
                try { old = TranslationMemory.Read(store.MemoryFile); } catch { old = null; }
            }
            var merged = old?.MergedWith(fresh) ?? fresh;
            var tmp = store.MemoryFile + ".tmp";
            merged.WriteTo(tmp);
            if (File.Exists(store.MemoryFile)) File.Delete(store.MemoryFile);
            File.Move(tmp, store.MemoryFile);
            store.MemorySize = merged.Count;
            store.MemoryAt = PatchSource.Now();
            store.MemoryPatchSha = patchSha;
            log($"번역 메모리 갱신 · {merged.Count:N0}줄");
        }

        /// <summary>설치마다 처음 한 번, 게임 폴더에 있던 파일을 백업 (원본 복원용)</summary>
        private static string? BackupIfFirst(Store.Target t, string path, Inspection info)
        {
            if (!info.Exists || File.Exists(t.BackupFile)) return null;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(t.BackupFile)!);
                File.Copy(path, t.BackupFile, false);
                return null;
            }
            catch (Exception e)
            {
                return e.Message;
            }
        }

        /// <summary>게임 폴더에 쓴다. 권한이 없으면 이 파일 복사만 관리자 권한으로 (UAC).</summary>
        private string? WriteGameFile(string src, string dst)
        {
            try
            {
                File.Copy(src, dst, true);
                return null;
            }
            catch (UnauthorizedAccessException)
            {
                log("게임 폴더에 쓸 권한이 없어 관리자 권한으로 복사합니다");
                var code = Elevated.Copy(src, dst);
                return code switch
                {
                    0 => null,
                    null => "관리자 권한 요청이 취소됐습니다",
                    _ => "관리자 권한 복사 실패 (" + code + ")",
                };
            }
            catch (IOException e)
            {
                return e.Message;
            }
        }

        private ActionResult Apply(GameInstall g)
        {
            if (GameLocator.IsGameRunningAt(g.GameDir)) return new ActionResult(Outcome.GameRunning);
            if (!File.Exists(store.CacheFile) || store.CachedSha.Length == 0) return new ActionResult(Outcome.NoPatch);
            var info = Inspect(g);
            var patchLayout = PatchLayout();
            if (info.GameLayout.Length > 0 && patchLayout.Length > 0 && info.GameLayout != patchLayout)
                return new ActionResult(Outcome.VersionMismatch);
            var t = store.For(g.GameDir);
            if (info.Exists && info.Sha == store.CachedSha)
            {
                t.AppliedSha = store.CachedSha;
                t.RepairedSha = "";
                return new ActionResult(Outcome.Same);
            }
            var err = BackupIfFirst(t, g.TargetFile, info) ?? WriteGameFile(store.CacheFile, g.TargetFile);
            if (err != null) return new ActionResult(Outcome.Failed, err);
            if (PatchSource.Sha256(g.TargetFile) != store.CachedSha) return new ActionResult(Outcome.Failed, "복사 후 해시가 다릅니다");
            t.AppliedSha = store.CachedSha;
            t.AppliedAt = PatchSource.Now();
            t.RepairedSha = "";
            // 이 버전 공식 원본을 보관해 뒀으면 번역 메모리를 최신 번역으로 갱신
            if (File.Exists(t.OfficialFile) && t.OfficialLayout == patchLayout)
            {
                try { BuildMemory(t.OfficialFile, store.CacheFile, store.CachedSha); }
                catch (Exception e) { log("번역 메모리 갱신 실패: " + e.Message); }
            }
            return new ActionResult(Outcome.Done);
        }

        private ActionResult Repair(GameInstall g)
        {
            if (GameLocator.IsGameRunningAt(g.GameDir)) return new ActionResult(Outcome.GameRunning);
            var info = Inspect(g);
            var t = store.For(g.GameDir);
            if (info.GameLayout.Length == 0 || !File.Exists(t.OfficialFile) || t.OfficialLayout != info.GameLayout)
                return new ActionResult(Outcome.NoOfficial);
            if (!File.Exists(store.MemoryFile)) return new ActionResult(Outcome.NoMemory);

            var result = PatchRepair.Repair(LangTable.Read(t.OfficialFile), TranslationMemory.Read(store.MemoryFile));
            result.Table.WriteTo(t.RepairedFile);
            var sha = PatchSource.Sha256(t.RepairedFile);
            var err = BackupIfFirst(t, g.TargetFile, info) ?? WriteGameFile(t.RepairedFile, g.TargetFile);
            if (err != null) return new ActionResult(Outcome.Failed, err);
            if (PatchSource.Sha256(g.TargetFile) != sha) return new ActionResult(Outcome.Failed, "복사 후 해시가 다릅니다");
            t.RepairedSha = sha;
            t.RepairedAt = PatchSource.Now();
            t.RepairedCoverage = result.Coverage;
            store.PutLayout(sha, t.OfficialLayout);
            log($"임시 복구: 한국어 {result.Translated:N0}줄, 중국어로 남음 {result.LeftChinese:N0}줄");
            return new ActionResult(Outcome.Done, "", result.Coverage);
        }

        /// <summary>처음 적용할 때 백업한 파일로 되돌린다. 백업이 이전 게임 버전이면 되돌리지 않는다 (문장이 엉뚱해진다).</summary>
        private ActionResult Restore(GameInstall g)
        {
            var t = store.For(g.GameDir);
            if (!File.Exists(t.BackupFile)) return new ActionResult(Outcome.NoBackup);
            if (GameLocator.IsGameRunningAt(g.GameDir)) return new ActionResult(Outcome.GameRunning);
            var info = Inspect(g);
            var backupLayout = LangTable.LayoutKeyOf(LangTable.ReadIds(t.BackupFile));
            if (info.GameLayout.Length > 0 && backupLayout != info.GameLayout) return new ActionResult(Outcome.OldVersion);
            var err = WriteGameFile(t.BackupFile, g.TargetFile);
            if (err != null) return new ActionResult(Outcome.Failed, err);
            t.AppliedSha = "";
            t.RepairedSha = "";
            return new ActionResult(Outcome.Done);
        }

        private static string Short(string layout)
        {
            int i = layout.IndexOf(':');
            return i >= 0 && layout.Length > i + 8 ? layout.Substring(i + 1, 8) : layout;
        }
    }
}
