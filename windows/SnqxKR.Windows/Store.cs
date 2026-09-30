using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace SnqxKR
{
    /// <summary>
    /// 앱 데이터 (설정·한패 캐시·공식 원본 보관·번역 메모리)는 %LOCALAPPDATA%\SnqxKR 에 둔다.
    /// 수백 MB 가 될 수 있어 로밍 프로필(AppData\Roaming)이 아니라 Local 이다. exe 는 어디에 두든 같은 데이터를 쓴다.
    /// 설정은 key=value 한 줄씩인 settings.ini.
    /// </summary>
    public sealed class Store
    {
        public string Root { get; }
        private readonly string settingsPath;
        private readonly Dictionary<string, string> map = new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly object gate = new object();

        /// <summary>0.2 의 exe 옆 데이터 폴더를 옮겼으면 그 경로</summary>
        public string? MigratedFrom { get; private set; }
        /// <summary>옮기다 실패했으면 그 이유 (남은 파일은 다음 실행 때 다시 옮긴다)</summary>
        public string? MigrationError { get; private set; }

        public Store()
        {
            Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SnqxKR");
            Directory.CreateDirectory(Root);
            settingsPath = Path.Combine(Root, "settings.ini");
            MigrateFrom(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "SnqxKR-data"));
            if (File.Exists(settingsPath))
            {
                foreach (var line in File.ReadAllLines(settingsPath, Encoding.UTF8))
                {
                    int eq = line.IndexOf('=');
                    if (eq > 0) map[line.Substring(0, eq)] = line.Substring(eq + 1);
                }
            }
        }

        /// <summary>
        /// 0.2 는 exe 옆 SnqxKR-data 에 저장했다. 새 위치에 설정이 없을 때 한 번 옮긴다.
        /// settings.ini 는 맨 마지막에 옮겨서, 중간에 끊겨도 다음 실행 때 나머지를 마저 옮긴다.
        /// </summary>
        private void MigrateFrom(string old)
        {
            if (!Directory.Exists(old) || File.Exists(settingsPath)) return;
            try
            {
                var oldSettings = Path.Combine(old, "settings.ini");
                var files = Directory.GetFiles(old, "*", SearchOption.AllDirectories)
                    .OrderBy(f => string.Equals(f, oldSettings, StringComparison.OrdinalIgnoreCase) ? 1 : 0);
                foreach (var src in files)
                {
                    var dst = Path.Combine(Root, src.Substring(old.Length).TrimStart('\\'));
                    Directory.CreateDirectory(Path.GetDirectoryName(dst)!);
                    if (File.Exists(dst)) File.Delete(src);
                    else File.Move(src, dst); // 드라이브가 달라도 파일은 옮겨진다 (복사 후 삭제)
                }
                foreach (var dir in Directory.GetDirectories(old, "*", SearchOption.AllDirectories).OrderByDescending(d => d.Length))
                    if (!Directory.EnumerateFileSystemEntries(dir).Any()) Directory.Delete(dir);
                if (!Directory.EnumerateFileSystemEntries(old).Any()) Directory.Delete(old);
                MigratedFrom = old;
            }
            catch (Exception e)
            {
                MigrationError = e.Message;
            }
        }

        /// <summary>조건에 맞는 설정 키를 지운다 (모든 설치의 공식 원본 기록 초기화 등)</summary>
        public void RemoveKeys(Func<string, bool> match)
        {
            lock (gate)
            {
                var keys = map.Keys.Where(match).ToList();
                if (keys.Count == 0) return;
                foreach (var key in keys) map.Remove(key);
                Save();
            }
        }

        public string Get(string key)
        {
            lock (gate) return map.TryGetValue(key, out var v) ? v : "";
        }

        public void Set(string key, string value)
        {
            lock (gate)
            {
                if (map.TryGetValue(key, out var old) && old == value) return;
                map[key] = value.Replace("\r", " ").Replace("\n", " ");
                Save();
            }
        }

        private void Save()
        {
            var tmp = settingsPath + ".tmp";
            File.WriteAllLines(tmp, map.OrderBy(kv => kv.Key).Select(kv => kv.Key + "=" + kv.Value), Encoding.UTF8);
            if (File.Exists(settingsPath)) File.Replace(tmp, settingsPath, null);
            else File.Move(tmp, settingsPath);
        }

        public long GetLong(string key) => long.TryParse(Get(key), out var v) ? v : 0;
        public void SetLong(string key, long v) => Set(key, v.ToString());

        // ---- 한패 캐시 ----

        public string CacheFile => Path.Combine(Root, "patch", GameLocator.PatchFileName);
        public string CachedSha { get => Get("cachedSha"); set => Set("cachedSha", value); }
        public string Etag { get => Get("etag"); set => Set("etag", value); }
        public long CheckedAt { get => GetLong("checkedAt"); set => SetLong("checkedAt", value); }

        // ---- 번역 메모리 ----

        public string MemoryFile => Path.Combine(Root, "translation-memory.bin");
        public int MemorySize { get => (int)GetLong("memorySize"); set => SetLong("memorySize", value); }
        public long MemoryAt { get => GetLong("memoryAt"); set => SetLong("memoryAt", value); }
        public string MemoryPatchSha { get => Get("memoryPatchSha"); set => Set("memoryPatchSha", value); }

        /// <summary>파일 SHA-256 → 게임 버전 지문. 50MB 를 다시 훑지 않도록 기억해 둔다.</summary>
        public string? LayoutFor(string sha) { var v = Get("layout." + sha); return v.Length == 0 ? null : v; }
        public void PutLayout(string sha, string layout) => Set("layout." + sha, layout);

        /// <summary>전에 찾은 게임 폴더 (자동 관리, 매번 다시 검증). 사용자가 지정하는 값이 아니다.</summary>
        public List<string> KnownGameDirs
        {
            get => Get("knownGameDirs").Split(new[] { '|' }, StringSplitOptions.RemoveEmptyEntries).ToList();
            set => Set("knownGameDirs", string.Join("|", value.Distinct(StringComparer.OrdinalIgnoreCase)));
        }

        public Target For(string gameDir) => new Target(this, gameDir);

        /// <summary>게임 폴더(설치)마다 따로 두는 적용 기록·원본 백업·공식 원본 보관·복구본</summary>
        public sealed class Target
        {
            private readonly Store s;
            private readonly string key;

            public Target(Store store, string gameDir)
            {
                s = store;
                using var sha = SHA1.Create();
                var digest = sha.ComputeHash(Encoding.UTF8.GetBytes(Path.GetFullPath(gameDir).TrimEnd('\\').ToLowerInvariant()));
                key = string.Concat(digest.Take(6).Select(b => b.ToString("x2")));
            }

            private string Dir(string kind) => Path.Combine(s.Root, kind, key);

            /// <summary>처음 적용할 때 게임 폴더에 있던 파일 (원본 복원용, 한 번만 만든다)</summary>
            public string BackupFile => Path.Combine(Dir("backup"), GameLocator.PatchFileName + ".orig");
            /// <summary>지금 게임 버전의 공식 원본(중국어). 번역 메모리를 만들고 업데이트 후 복구할 때 쓴다.</summary>
            public string OfficialFile => Path.Combine(Dir("official"), GameLocator.PatchFileName);
            /// <summary>번역 메모리로 만든 임시 복구본</summary>
            public string RepairedFile => Path.Combine(Dir("repaired"), GameLocator.PatchFileName);

            public string AppliedSha { get => s.Get(k("appliedSha")); set => s.Set(k("appliedSha"), value); }
            public long AppliedAt { get => s.GetLong(k("appliedAt")); set => s.SetLong(k("appliedAt"), value); }
            public string OfficialSha { get => s.Get(k("officialSha")); set => s.Set(k("officialSha"), value); }
            public string OfficialLayout { get => s.Get(k("officialLayout")); set => s.Set(k("officialLayout"), value); }
            public string RepairedSha { get => s.Get(k("repairedSha")); set => s.Set(k("repairedSha"), value); }
            public long RepairedAt { get => s.GetLong(k("repairedAt")); set => s.SetLong(k("repairedAt"), value); }
            public double RepairedCoverage
            {
                get => double.TryParse(s.Get(k("repairedCoverage")), System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : -1;
                set => s.Set(k("repairedCoverage"), value.ToString(System.Globalization.CultureInfo.InvariantCulture));
            }

            /// <summary>"크기:수정시각|sha|한글수". 크기·수정시각이 그대로면 해시·본문 읽기를 건너뛴다.</summary>
            public string Seen { get => s.Get(k("seen")); set => s.Set(k("seen"), value); }

            private string k(string name) => "t." + key + "." + name;
        }
    }
}
