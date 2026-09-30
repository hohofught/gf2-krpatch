using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;

namespace SnqxKR
{
    /// <summary>찾아낸 GF2 PC 클라이언트 위치. IsCn 이 false 면 어떤 파일도 쓰면 안 된다.</summary>
    public sealed record GameInstall(string GameDir, string? LauncherDir, string Source, bool IsCn, string Channel, string ServerNote)
    {
        public string DataDir => Path.Combine(GameDir, "GF2_Exilium_Data");
        public string TableDir => Path.Combine(DataDir, "LocalCache", "Data", "Table");
        public string TargetFile => Path.Combine(TableDir, GameLocator.PatchFileName);
    }

    /// <summary>
    /// 디스크를 뒤지지 않고 런처/게임이 남긴 흔적에서 경로를 계산한다.
    ///  실행 중인 게임·런처 프로세스, 레지스트리, 바로가기, B服 기본 설치 경로, Everything 색인(떠 있으면), 전에 찾았던 경로(전체 스캔 결과 포함)
    /// 런처 폴더에서 게임 폴더를 얻는 규칙은 채널마다 다르다.
    ///  官服: PCLauncher.exe 옆 config.ini 의 game_install_path (없으면 "GF2 Game")
    ///  B服:  빌리빌리 런처(이름이 게임과 같은 GF2_Exilium.exe) 아래 "Games"
    /// 여기서는 후보만 모은다. 중섭 여부는 CnServerCheck 가 판정한다. 설치가 여러 개일 수 있어 전부 모은다.
    /// </summary>
    public static class GameLocator
    {
        public const string PatchFileName = "LangPackageTableCnData.bytes";
        private const string GameExe = "GF2_Exilium.exe";
        private const string OfficialLauncherExe = "PCLauncher.exe";
        private const string OfficialGameFolder = "GF2 Game";
        private const string BiliGameFolder = "Games";

        /// <param name="knownDirs">전에 찾았던 게임·런처 폴더(전체 스캔 결과 포함). 매번 다시 검증하고 없어졌으면 버린다.</param>
        /// <param name="note">기록에 남길 한 줄 (Everything 색인을 썼는지)</param>
        public static List<GameInstall> LocateAll(IEnumerable<string> knownDirs, Action<string>? note = null)
        {
            var found = new Dictionary<string, GameInstall>(StringComparer.OrdinalIgnoreCase);
            var tried = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            // 폴더 하나를 게임 폴더로도, 런처 폴더로도 확인한다 (B服 는 런처와 게임 실행 파일 이름이 같다)
            void Try(string? dir, string source)
            {
                if (string.IsNullOrWhiteSpace(dir)) return;
                string full;
                try { full = Path.GetFullPath(dir); } catch { return; }
                if (!tried.Add(full)) return;
                foreach (var install in new[] { FromGameDir(full, source) }.Concat(FromLauncherDir(full, source)))
                    if (install != null && !found.ContainsKey(install.GameDir)) found[install.GameDir] = install;
            }

            foreach (var (exePath, source) in RunningProcesses()) Try(Path.GetDirectoryName(exePath), source);
            foreach (var (dir, source) in RegistryDirs()) Try(dir, source);
            foreach (var (dir, source) in ShortcutDirs()) Try(dir, source);
            foreach (var dir in BiliDefaultDirs()) Try(dir, "B服 기본 설치 경로");
            // Everything 이 떠 있으면 그 색인에서 모든 드라이브를 본다 (관리자 권한·디스크 훑기 없이 수십 ms)
            var indexed = EverythingSearch.Find(new[] { GameExe, OfficialLauncherExe }, 3000, out var everything);
            note?.Invoke(everything);
            foreach (var path in indexed) Try(Path.GetDirectoryName(path), "Everything 색인");
            foreach (var dir in knownDirs) Try(dir, "전에 찾은 경로");

            return found.Values.ToList();
        }

        /// <summary>사용자가 고른 실행 파일(GF2_Exilium.exe / PCLauncher.exe)의 폴더</summary>
        public static GameInstall? FromPickedFolder(string folder) =>
            FromGameDir(folder, "직접 선택") ?? FromLauncherDir(folder, "직접 선택").FirstOrDefault(i => i != null);

        /// <summary>이 폴더의 게임 본체가 실행 중인지 (B服 런처도 이름이 GF2_Exilium.exe 라 폴더로 구분)</summary>
        public static bool IsGameRunningAt(string gameDir)
        {
            foreach (var (exePath, _) in RunningProcesses())
                if (string.Equals(Path.GetFileName(exePath), GameExe, StringComparison.OrdinalIgnoreCase) &&
                    string.Equals(Path.GetDirectoryName(exePath)?.TrimEnd('\\'), Path.GetFullPath(gameDir).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                    return true;
            return false;
        }

        private static GameInstall? FromGameDir(string? dir, string source, string? launcherDir = null)
        {
            if (string.IsNullOrWhiteSpace(dir)) return null;
            dir = Path.GetFullPath(dir);
            if (!File.Exists(Path.Combine(dir, GameExe))) return null;
            if (!Directory.Exists(Path.Combine(dir, "GF2_Exilium_Data"))) return null;
            var (isCn, channel, note) = CnServerCheck.Verify(dir);
            return new GameInstall(dir, launcherDir, source, isCn, channel, note);
        }

        private static IEnumerable<GameInstall?> FromLauncherDir(string? dir, string source)
        {
            if (string.IsNullOrWhiteSpace(dir)) yield break;
            if (File.Exists(Path.Combine(dir, OfficialLauncherExe)))
            {
                var configured = ReadGameInstallPath(Path.Combine(dir, "config.ini"));
                yield return FromGameDir(configured, source + " → config.ini", dir)
                             ?? FromGameDir(Path.Combine(dir, OfficialGameFolder), source + " → 기본 폴더", dir);
            }
            if (File.Exists(Path.Combine(dir, GameExe)))
                yield return FromGameDir(Path.Combine(dir, BiliGameFolder), source + " → Games", dir);
        }

        /// <summary>런처 config.ini 의 [launcher] game_install_path (슬래시 구분 경로)</summary>
        internal static string? ReadGameInstallPath(string ini)
        {
            if (!File.Exists(ini)) return null;
            bool inLauncher = false;
            foreach (var raw in File.ReadLines(ini))
            {
                var line = raw.Trim();
                if (line.StartsWith("["))
                {
                    inLauncher = string.Equals(line, "[launcher]", StringComparison.OrdinalIgnoreCase);
                    continue;
                }
                if (!inLauncher) continue;
                int eq = line.IndexOf('=');
                if (eq > 0 && string.Equals(line.Substring(0, eq).Trim(), "game_install_path", StringComparison.OrdinalIgnoreCase))
                    return line.Substring(eq + 1).Trim().Trim('"').Replace('/', '\\');
            }
            return null;
        }

        private static IEnumerable<(string ExePath, string Source)> RunningProcesses()
        {
            foreach (var exe in new[] { GameExe, OfficialLauncherExe })
            {
                foreach (var p in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(exe)))
                {
                    var path = ImagePath(p.Id);
                    p.Dispose();
                    if (path != null) yield return (path, "실행 중인 " + exe);
                }
            }
        }

        /// <summary>
        /// 官服: Uninstall\GF2Exilium (32비트 설치기라 WOW6432Node). InstallLocation 이 비어 있어 아이콘·제거 경로에서 폴더를 얻는다.
        /// B服: SOFTWARE\GF2_Exilium 의 GameInstallPath, 그리고 제거 정보(표시 이름이 중국어).
        /// </summary>
        private static IEnumerable<(string Dir, string Source)> RegistryDirs()
        {
            const string uninstall = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall";
            var list = new List<(string, string)>();
            foreach (var hive in new[] { RegistryHive.LocalMachine, RegistryHive.CurrentUser })
            foreach (var view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
            {
                try
                {
                    using var baseKey = RegistryKey.OpenBaseKey(hive, view);
                    using (var bili = baseKey.OpenSubKey(@"SOFTWARE\GF2_Exilium"))
                        if (bili?.GetValue("GameInstallPath") is string installPath && installPath.Trim().Length > 0)
                            list.Add((installPath, @"레지스트리 SOFTWARE\GF2_Exilium"));

                    using var root = baseKey.OpenSubKey(uninstall);
                    if (root == null) continue;
                    foreach (var name in root.GetSubKeyNames())
                    {
                        using var key = root.OpenSubKey(name);
                        if (key == null) continue;
                        var icon = key.GetValue("DisplayIcon") as string;
                        var uninstallCmd = key.GetValue("UninstallString") as string;
                        var display = key.GetValue("DisplayName") as string ?? "";
                        if (!IsGf2Path(icon) && !IsGf2Path(uninstallCmd) &&
                            display.IndexOf("EXILIUM", StringComparison.OrdinalIgnoreCase) < 0 && display.IndexOf("少女前线2", StringComparison.Ordinal) < 0)
                            continue;
                        foreach (var dir in new[] { key.GetValue("InstallLocation") as string, DirOf(icon), DirOf(uninstallCmd) })
                            if (!string.IsNullOrWhiteSpace(dir)) list.Add((dir!, "레지스트리 " + name));
                    }
                }
                catch
                {
                    // 레지스트리 한 곳을 못 읽어도 나머지는 본다
                }
            }
            return list;
        }

        private static IEnumerable<(string Dir, string Source)> ShortcutDirs()
        {
            var list = new List<(string, string)>();
            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return list;
            dynamic shell = Activator.CreateInstance(shellType)!;
            foreach (var folder in new[]
                     {
                         Environment.SpecialFolder.CommonDesktopDirectory, Environment.SpecialFolder.DesktopDirectory,
                         Environment.SpecialFolder.CommonPrograms, Environment.SpecialFolder.Programs,
                     })
            {
                var dir = Environment.GetFolderPath(folder);
                foreach (var lnk in LinksIn(dir))
                {
                    string? target = null;
                    try { target = (string)shell.CreateShortcut(lnk).TargetPath; } catch { }
                    if (IsGf2Path(target)) list.Add((Path.GetDirectoryName(target)!, "바로가기 " + Path.GetFileName(lnk)));
                }
            }
            return list;
        }

        /// <summary>폴더와 한 단계 아래 폴더의 .lnk (시작 메뉴는 게임 이름 폴더 안에 있다)</summary>
        private static IEnumerable<string> LinksIn(string dir)
        {
            var result = new List<string>();
            if (!Directory.Exists(dir)) return result;
            try { result.AddRange(Directory.GetFiles(dir, "*.lnk")); } catch { }
            string[] subs;
            try { subs = Directory.GetDirectories(dir); } catch { return result; }
            foreach (var sub in subs)
                try { result.AddRange(Directory.GetFiles(sub, "*.lnk")); } catch { }
            return result;
        }

        /// <summary>B服 런처의 기본 설치 위치: C:\Program Files\bilibili Game\GF2_Exilium</summary>
        private static IEnumerable<string> BiliDefaultDirs()
        {
            foreach (var pf in new[] { Environment.SpecialFolder.ProgramFiles, Environment.SpecialFolder.ProgramFilesX86 })
                yield return Path.Combine(Environment.GetFolderPath(pf), "bilibili Game", "GF2_Exilium");
        }

        private static bool IsGf2Path(string? path) =>
            path != null && (path.IndexOf(OfficialLauncherExe, StringComparison.OrdinalIgnoreCase) >= 0 ||
                             path.IndexOf(GameExe, StringComparison.OrdinalIgnoreCase) >= 0);

        /// <summary>"C:\x\uninst.exe" /S, C:\x\PCLauncher.exe,0 같은 명령줄에서 폴더만 뽑는다</summary>
        private static string? DirOf(string? command)
        {
            if (string.IsNullOrWhiteSpace(command)) return null;
            var s = command!.Trim();
            if (s.StartsWith("\""))
            {
                int end = s.IndexOf('"', 1);
                s = end > 0 ? s.Substring(1, end - 1) : s.Trim('"');
            }
            else
            {
                int exe = s.IndexOf(".exe", StringComparison.OrdinalIgnoreCase);
                if (exe > 0) s = s.Substring(0, exe + 4);
            }
            try { return Path.GetDirectoryName(s); } catch { return null; }
        }

        // Process.MainModule 은 관리자 권한으로 뜬 프로세스를 못 읽어서 제한 권한으로 경로만 묻는다
        private const int ProcessQueryLimitedInformation = 0x1000;

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern SafeProcessHandle OpenProcess(int access, bool inherit, int pid);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool QueryFullProcessImageName(SafeProcessHandle process, int flags, StringBuilder name, ref int size);

        private static string? ImagePath(int pid)
        {
            using var h = OpenProcess(ProcessQueryLimitedInformation, false, pid);
            if (h.IsInvalid) return null;
            var buf = new StringBuilder(1024);
            int size = buf.Capacity;
            return QueryFullProcessImageName(h, 0, buf, ref size) ? buf.ToString(0, size) : null;
        }
    }
}
