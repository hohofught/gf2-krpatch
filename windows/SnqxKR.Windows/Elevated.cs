using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace SnqxKR
{
    /// <summary>
    /// 관리자 권한이 필요한 일만 이 exe 를 관리자 권한으로 한 번 더 띄워서 한다 (앱 자체는 일반 권한).
    ///  --scan &lt;결과파일&gt; [C: D: ...] : NTFS MFT 로 고른 드라이브(없으면 고정 NTFS 드라이브 전부)에서 게임·런처 실행 파일 찾기
    ///  --copy &lt;원본&gt; &lt;대상&gt; : Program Files 등 쓰기 권한이 없는 게임 폴더에 한패 파일 복사
    /// 관리자 쪽 명령도 대상이 중섭 게임 폴더의 한패 파일인지 다시 확인한다.
    /// </summary>
    public static class Elevated
    {
        private static string ExePath => Process.GetCurrentProcess().MainModule!.FileName;

        /// <summary>관리자 권한으로 이 exe 를 띄워 끝날 때까지 기다린다. UAC 를 취소하면 null.</summary>
        private static int? Run(string args)
        {
            int? result = null;
            // ShellExecute(runas)는 STA 스레드에서 부른다
            var th = new Thread(() =>
            {
                try
                {
                    using var p = Process.Start(new ProcessStartInfo(ExePath, args)
                    {
                        UseShellExecute = true,
                        Verb = "runas",
                        WindowStyle = ProcessWindowStyle.Hidden,
                    })!;
                    p.WaitForExit();
                    result = p.ExitCode;
                }
                catch (Win32Exception e) when (e.NativeErrorCode == 1223)
                {
                    result = null; // 사용자가 UAC 를 취소
                }
            });
            th.SetApartmentState(ApartmentState.STA);
            th.Start();
            th.Join();
            return result;
        }

        public static int? Copy(string src, string dst) => Run($"--copy \"{src}\" \"{dst}\"");

        /// <param name="drives">훑을 드라이브 ("C:" 꼴)</param>
        public static int? Scan(string resultFile, IEnumerable<string> drives) =>
            Run($"--scan \"{resultFile}\" " + string.Join(" ", drives.Where(IsDrive)));

        /// <summary>"C:" 꼴 (관리자 쪽으로 넘기는 인자를 드라이브 글자로만 한정한다)</summary>
        private static bool IsDrive(string s) => s.Length == 2 && s[1] == ':' && char.IsLetter(s[0]) && s[0] < 128;

        /// <summary>관리자 권한으로 실행된 쪽. 창 없이 일만 하고 종료 코드를 돌려준다.</summary>
        public static int Handle(string[] args)
        {
            try
            {
                switch (args[0])
                {
                    case "--scan" when args.Length >= 2:
                        var drives = args.Skip(2).ToList();
                        if (drives.Any(d => !IsDrive(d))) return 64;
                        var stats = new List<MftScanner.VolumeStats>();
                        var hits = MftScanner.Find(new[] { "GF2_Exilium.exe", "PCLauncher.exe" }, stats, drives.Count > 0 ? drives : null);
                        // "#드라이브 레코드수 ms 찾은수" (훑지 못하면 "!드라이브 이유") 줄 뒤에 찾은 파일 경로
                        var lines = stats.OrderBy(s => s.Drive)
                            .Select(s => s.Error == null ? $"#{s.Drive} {s.Records} {s.Milliseconds} {s.Hits}" : $"!{s.Drive} {s.Error.Replace('\r', ' ').Replace('\n', ' ')}")
                            .Concat(hits);
                        File.WriteAllLines(args[1], lines, Encoding.UTF8);
                        return 0;

                    case "--copy" when args.Length == 3:
                        return SafeCopy(args[1], args[2]);

                    default:
                        return 64;
                }
            }
            catch (Exception e)
            {
                try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "SnqxKR-elevated.log"), e.ToString()); } catch { }
                return 1;
            }
        }

        /// <summary>대상이 중섭 게임 폴더의 LangPackageTableCnData.bytes 일 때만 복사한다</summary>
        private static int SafeCopy(string src, string dst)
        {
            if (!File.Exists(src)) return 2;
            var full = Path.GetFullPath(dst);
            if (!string.Equals(Path.GetFileName(full), GameLocator.PatchFileName, StringComparison.OrdinalIgnoreCase)) return 3;
            // ...\<게임>\GF2_Exilium_Data\LocalCache\Data\Table\LangPackageTableCnData.bytes
            var table = Path.GetDirectoryName(full)!;
            var parts = new[] { "Table", "Data", "LocalCache", "GF2_Exilium_Data" };
            var dir = table;
            foreach (var expected in parts)
            {
                if (!string.Equals(Path.GetFileName(dir), expected, StringComparison.OrdinalIgnoreCase)) return 3;
                dir = Path.GetDirectoryName(dir)!;
            }
            var (isCn, _, _) = CnServerCheck.Verify(dir);
            if (!isCn) return 4;
            Directory.CreateDirectory(table);
            File.Copy(src, full, true);
            return 0;
        }
    }
}
