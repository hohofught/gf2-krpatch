using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
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

    /// <summary>
    /// 게임 폴더 한 곳의 상태. PatchAligned: 받아 둔 한패가 이 게임 버전용일 때, 보관한 공식 원문과 자리가 맞는지
    /// (true 맞음, false 어긋남, null 판단 못 함 — 공식 원문이 없거나 한패가 다른 버전)
    /// </summary>
    public sealed record Inspection(bool Exists, string Sha, long Size, PatchStatus Status, string GameLayout, bool Running,
        bool? PatchAligned = null, double AlignRatio = -1);

    public enum Outcome { Done, Same, GameRunning, NoPatch, VersionMismatch, Misaligned, NoOfficial, NoMemory, NoBackup, OldVersion, Failed }

    public sealed record ActionResult(Outcome Outcome, string Message = "", double Coverage = -1, int FromPatch = 0);

    /// <summary>저장 공간 (바이트). Cache 는 받은 한패 + 임시 복구본이라 다시 받거나 만들 수 있다.</summary>
    public sealed record StorageInfo(long Cache, long Memory, long Official, long Backup)
    {
        public long Total => Cache + Memory + Official + Backup;
    }

    /// <summary>
    /// 한패 적용·임시 복구·원본 복원 (안드로이드 PatchEngine 과 같은 규칙).
    ///  - 게임 폴더에 공식 원본이 보이면 보관하고, 같은 버전 한패가 있으면 번역 메모리에 넣는다
    ///  - 한패와 게임의 버전 지문(Id 집합)이 다르면 옛 한패를 그대로 넣지 않고, 임시 복구로
    ///    새 원본을 번역 메모리로 한국어화한 뒤 옛 한패의 번역을 새 자리로 옮겨 넣는다
    ///  - 원본 복원은 백업이 지금 게임 버전일 때만 한다
    /// 중섭(CnServerCheck 통과) 설치에만 쓴다. 쓰기 권한이 없으면(Program Files 의 B服 등) 그 파일 복사만 관리자 권한으로 한다.
    /// </summary>
    public sealed class PatchService
    {
        private const int HangulSampleBytes = 2 * 1024 * 1024;
        private static readonly SemaphoreSlim Gate = new SemaphoreSlim(1, 1);

        /// <summary>보관하다 실패한 공식 원본의 해시 (Gate 안에서만). 앱을 다시 켜면 한 번 더 해 본다</summary>
        private static readonly HashSet<string> CaptureFailed = new HashSet<string>();

        private readonly Store store;
        private readonly Action<string> log;
        private readonly IEngine engine = Engines.Current;

        public PatchService(Store store, Action<string> log)
        {
            this.store = store;
            this.log = log;
        }

        /// <summary>지금 쓰는 번역 엔진 이름 (C# / 네이티브)</summary>
        public string EngineName => engine.Name;

        public Task<Inspection> InspectAsync(GameInstall g) => Locked(() => Inspect(g));
        /// <summary>force: 자리 검사에 걸린 한패도 사용자가 원하면 넣는다 (번역 메모리에는 넣지 않는다)</summary>
        public Task<ActionResult> ApplyAsync(GameInstall g, bool force = false) => Locked(() => Apply(g, force));
        public Task<ActionResult> RepairAsync(GameInstall g) => Locked(() => Repair(g));
        public Task<ActionResult> RestoreAsync(GameInstall g) => Locked(() => Restore(g));
        public Task<string> PatchLayoutAsync() => Locked(PatchLayout);
        public Task<long> ClearCacheAsync() => Locked(ClearCache);
        public Task<long> DeleteMemoryAsync() => Locked(DeleteMemory);
        public Task<long> DeleteOfficialAsync() => Locked(DeleteOfficial);
        public Task<long> DeleteBackupsAsync() => Locked(() => DeleteDir("backup"));

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

            // 보관에 실패해도 상태 확인은 계속한다. 같은 파일로 복사를 되풀이하지 않게 이 실행 동안 기억한다 (안드로이드와 같음)
            if (status == PatchStatus.Official && t.OfficialSha != sha && !CaptureFailed.Contains(sha))
            {
                try { CaptureOfficial(g, path, sha); }
                catch (Exception e) { CaptureFailed.Add(sha); log("공식 원본 보관 실패: " + e.Message); }
            }
            var layout = store.LayoutFor(sha);
            if (layout == null)
            {
                layout = status == PatchStatus.PatchedLatest ? PatchLayout() : engine.LayoutKey(path);
                if (layout.Length > 0) store.PutLayout(sha, layout);
            }
            // 받아 둔 한패가 이 게임 버전용이면 보관한 공식 원문과 자리가 맞는지 본다 (업데이트 직후 올라온 한패가 깨져 있던 적이 있다)
            var a = layout.Length > 0 && PatchLayout() == layout ? AlignmentWith(t, layout) : null;
            return new Inspection(true, sha, fi.Length, status, layout, running, a?.Ok, a?.Ratio ?? -1);
        }

        /// <summary>
        /// 받아 둔 한패와 이 설치의 공식 원문 보관본의 자리 검사. 같은 버전 원문이 없으면 null.
        /// 50MB 두 개를 읽으므로 (한패, 원문) 짝마다 한 번만 하고 결과를 기억한다.
        /// </summary>
        private PatchRepair.Alignment? AlignmentWith(Store.Target t, string layout)
        {
            if (!File.Exists(t.OfficialFile) || t.OfficialLayout != layout || !File.Exists(store.CacheFile)) return null;
            var key = "align." + Head(store.CachedSha) + "." + Head(t.OfficialSha);
            var known = store.Get(key).Split('/');
            if (known.Length == 2 && int.TryParse(known[0], out var u) && int.TryParse(known[1], out var m))
                return new PatchRepair.Alignment(u, m);
            var a = engine.Alignment(t.OfficialFile, store.CacheFile);
            store.Set(key, a.Untranslated + "/" + a.Matching);
            if (a.Ok == false) log($"받아 둔 한패가 공식 원문과 자리가 맞지 않습니다 (번역 안 된 줄 {a.Untranslated:N0}개 중 {a.Ratio:P0} 일치)");
            return a;
        }

        private static string Head(string sha) => sha.Length > 16 ? sha.Substring(0, 16) : sha;

        /// <summary>
        /// 게임 폴더의 공식 원본을 앱 데이터로 가져와 둔다. 번역 메모리를 만들고, 업데이트 후 복구할 때 쓴다.
        /// 옆 파일에 받아 해시·버전 지문까지 확인한 뒤 바꾼다 (실패해도 전에 보관한 원본과 기록이 어긋나지 않게).
        /// </summary>
        private void CaptureOfficial(GameInstall g, string path, string sha)
        {
            var t = store.For(g.GameDir);
            Directory.CreateDirectory(Path.GetDirectoryName(t.OfficialFile)!);
            var tmp = t.OfficialFile + ".new";
            string layout;
            try
            {
                File.Copy(path, tmp, true);
                if (PatchSource.Sha256(tmp) != sha) { log("공식 원본 보관 검증 실패"); return; }
                layout = engine.LayoutKey(tmp);
                if (File.Exists(t.OfficialFile)) File.Delete(t.OfficialFile);
                File.Move(tmp, t.OfficialFile);
            }
            finally
            {
                if (File.Exists(tmp)) File.Delete(tmp);
            }
            t.OfficialSha = sha;
            t.OfficialLayout = layout;
            store.PutLayout(sha, layout);
            log("게임 공식 원본 보관 (버전 지문 " + Short(layout) + ")");
            // 같은 버전 한패를 이미 받아 뒀으면 바로 번역 메모리에 넣는다 (실패해도 보관은 끝났다. 적용할 때 다시 한다)
            if (File.Exists(store.CacheFile) && PatchLayout() == layout)
            {
                try { BuildMemory(t.OfficialFile, store.CacheFile, store.CachedSha); }
                catch (Exception e) { log("번역 메모리 갱신 실패: " + e.Message); }
            }
        }

        /// <summary>받아 둔 한패의 버전 지문. 캐시를 지워도 기억해 둔 값으로 답한다.</summary>
        private string PatchLayout()
        {
            var sha = store.CachedSha;
            if (sha.Length == 0) return "";
            var known = store.LayoutFor(sha);
            if (known != null) return known;
            if (!File.Exists(store.CacheFile)) return "";
            var layout = engine.LayoutKey(store.CacheFile);
            store.PutLayout(sha, layout);
            return layout;
        }

        /// <summary>
        /// 같은 버전의 공식 원본 + 한패로 번역 메모리를 만들어 기존 것과 합친다 (새 번역이 이긴다).
        /// 파일이 <see cref="TranslationMemory.MaxBytes"/> 를 넘으면 가장 오래전에 본 줄부터 뺀다.
        /// </summary>
        private void BuildMemory(string officialFile, string patchFile, string patchSha)
        {
            if (store.MemoryPatchSha == patchSha && File.Exists(store.MemoryFile)) return;
            var tmp = store.MemoryFile + ".tmp";
            MemoryBuild r;
            try { r = engine.BuildMemory(officialFile, patchFile, store.MemoryFile, tmp, TranslationMemory.MaxBytes); }
            catch
            {
                if (File.Exists(tmp)) File.Delete(tmp); // 기존 메모리는 그대로 둔다
                throw;
            }
            if (!r.Built)
            {
                // 번역이 엉뚱한 원문과 짝지어져 메모리가 망가지는 것을 막는다
                log($"한패가 공식 원문과 자리가 맞지 않아 번역 메모리에 넣지 않았습니다 ({r.Alignment.Ratio:P0} 일치)");
                return;
            }
            if (r.Dropped > 0) log($"번역 메모리가 최대 크기를 넘어 오래된 {r.Dropped:N0}줄을 뺐습니다");
            if (File.Exists(store.MemoryFile)) File.Delete(store.MemoryFile);
            File.Move(tmp, store.MemoryFile);
            store.MemorySize = r.Size;
            store.MemoryAt = PatchSource.Now();
            store.MemoryPatchSha = patchSha;
            log($"번역 메모리 갱신 · {r.Size:N0}줄");
        }

        /// <summary>
        /// 설치마다 처음 한 번, 게임 폴더의 공식 원본을 백업 (원본 복원용).
        /// 다른 도구로 넣은 한패 같은 원본이 아닌 파일은 백업하지 않는다 (복원하면 그 한패로 돌아간다).
        /// </summary>
        private static string? BackupIfFirst(Store.Target t, string path, Inspection info)
        {
            if (!info.Exists || info.Status != PatchStatus.Official || File.Exists(t.BackupFile)) return null;
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

        private ActionResult Apply(GameInstall g, bool force)
        {
            if (GameLocator.IsGameRunningAt(g.GameDir)) return new ActionResult(Outcome.GameRunning);
            if (!File.Exists(store.CacheFile) || store.CachedSha.Length == 0) return new ActionResult(Outcome.NoPatch);
            var info = Inspect(g);
            var patchLayout = PatchLayout();
            if (info.GameLayout.Length > 0 && patchLayout.Length > 0 && info.GameLayout != patchLayout)
                return new ActionResult(Outcome.VersionMismatch);
            // 버전은 맞아도 번역이 엉뚱한 자리에 들어간 한패는 넣지 않는다 (같은 버전 공식 원문이 있을 때만 알 수 있다)
            // 번역 수정판은 이 검사에 걸리지 않는다 (번역 안 된 줄의 자리만 본다). 그래도 걸리면 사용자가 force 로 넣을 수 있다
            if (info.PatchAligned == false && !force) return new ActionResult(Outcome.Misaligned, "", info.AlignRatio);
            if (info.PatchAligned == false) log("자리 검사에 걸린 한패를 사용자 요청으로 넣습니다");
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

            if (!File.Exists(store.CacheFile)) log("받아 둔 한패가 없어 번역 메모리만으로 복구합니다");
            Directory.CreateDirectory(Path.GetDirectoryName(t.RepairedFile)!);
            RepairOutput result;
            try
            {
                result = engine.Repair(t.OfficialFile, store.MemoryFile, OldPatch(info), t.RepairedFile);
            }
            catch (OutOfMemoryException)
            {
                // 표 세 개(새 원본·메모리·옛 한패)를 한꺼번에 못 올리면 메모리만으로 복구한다
                log("메모리가 부족해 옛 한패 번역 옮기기를 건너뜁니다");
                result = engine.Repair(t.OfficialFile, store.MemoryFile, null, t.RepairedFile);
            }
            if (result.FromPatch > 0) log($"업데이트 전 한패에서 번역 {result.FromPatch:N0}줄을 새 자리로 옮김");
            var sha = result.Sha256; // 쓰면서 구한 해시 (다시 읽지 않는다)
            var err = BackupIfFirst(t, g.TargetFile, info) ?? WriteGameFile(t.RepairedFile, g.TargetFile);
            if (err != null) return new ActionResult(Outcome.Failed, err);
            if (PatchSource.Sha256(g.TargetFile) != sha) return new ActionResult(Outcome.Failed, "복사 후 해시가 다릅니다");
            t.RepairedSha = sha;
            t.RepairedAt = PatchSource.Now();
            t.RepairedCoverage = result.Coverage;
            store.PutLayout(sha, t.OfficialLayout);
            log($"임시 복구: 한국어 {result.Translated:N0}줄, 중국어로 남음 {result.LeftChinese:N0}줄");
            return new ActionResult(Outcome.Done, "", result.Coverage, result.FromPatch);
        }

        /// <summary>
        /// 임시 복구 2단계에 쓸 옛 한패 경로: 받아 둔 한패가 게임과 다른 버전(업데이트 전 한패)이고
        /// 번역 메모리에 아직 들어가지 않은 것일 때만. 메모리가 이미 이 한패로 만들어졌으면 옮길 게 없고,
        /// 바뀐 원문에 옛 번역을 얹게 된다. 같은 버전인데 자리가 어긋난 한패는 쓰지 않는다.
        /// </summary>
        private string? OldPatch(Inspection info)
        {
            var sha = store.CachedSha;
            if (sha.Length == 0 || !File.Exists(store.CacheFile) || sha == store.MemoryPatchSha) return null;
            if (PatchLayout() == info.GameLayout) return null;
            return store.CacheFile;
        }

        // ---------------- 저장 공간 ----------------

        public StorageInfo Storage() => new StorageInfo(
            DirSize("patch") + DirSize("repaired"),
            File.Exists(store.MemoryFile) ? new FileInfo(store.MemoryFile).Length : 0,
            DirSize("official"),
            DirSize("backup"));

        private long DirSize(string kind)
        {
            var dir = Path.Combine(store.Root, kind);
            return Directory.Exists(dir) ? Directory.GetFiles(dir, "*", SearchOption.AllDirectories).Sum(f => new FileInfo(f).Length) : 0;
        }

        private long DeleteDir(string kind)
        {
            long size = DirSize(kind);
            var dir = Path.Combine(store.Root, kind);
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            return size;
        }

        /// <summary>받은 한패·임시 복구본을 지운다. 기록(해시·버전 지문)은 남겨 상태 판정은 그대로다.</summary>
        private long ClearCache() => DeleteDir("patch") + DeleteDir("repaired");

        /// <summary>번역 메모리를 지운다. 다음에 공식 원본과 같은 버전 한패가 모이면 다시 만든다.</summary>
        private long DeleteMemory()
        {
            long size = File.Exists(store.MemoryFile) ? new FileInfo(store.MemoryFile).Length : 0;
            if (size > 0) File.Delete(store.MemoryFile);
            store.MemorySize = 0;
            store.MemoryAt = 0;
            store.MemoryPatchSha = "";
            return size;
        }

        /// <summary>공식 원본 보관본을 지운다. 게임 파일이 원본이면 다음 확인 때 다시 보관한다.</summary>
        private long DeleteOfficial()
        {
            long size = DeleteDir("official");
            store.RemoveKeys(k => k.StartsWith("t.", StringComparison.Ordinal) &&
                                  (k.EndsWith(".officialSha", StringComparison.Ordinal) || k.EndsWith(".officialLayout", StringComparison.Ordinal)));
            return size;
        }

        /// <summary>처음 적용할 때 백업한 파일로 되돌린다. 백업이 이전 게임 버전이면 되돌리지 않는다 (문장이 엉뚱해진다).</summary>
        private ActionResult Restore(GameInstall g)
        {
            var t = store.For(g.GameDir);
            if (!File.Exists(t.BackupFile)) return new ActionResult(Outcome.NoBackup);
            if (GameLocator.IsGameRunningAt(g.GameDir)) return new ActionResult(Outcome.GameRunning);
            var info = Inspect(g);
            var backupLayout = engine.LayoutKey(t.BackupFile);
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
