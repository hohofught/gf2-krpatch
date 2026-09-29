using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography.X509Certificates;

namespace SnqxKR
{
    /// <summary>
    /// 중섭(중국 서버: 官服, B服) PC 클라이언트인지 확인한다.
    /// 글로벌·한국 서버 클라이언트에는 절대 쓰면 안 되므로, 중섭 표식이 전부 확인될 때만 통과시킨다.
    /// 하나라도 다르거나 읽을 수 없으면 실패다 (애매하면 건드리지 않는다).
    /// </summary>
    public static class CnServerCheck
    {
        /// <summary>GF2_Exilium_Data\app.info = Unity 회사명 + "\n" + 제품명. 중섭은 중국어 제품명이다.</summary>
        private const string CnAppInfo = "SunBorn\n少女前线2：追放";

        /// <summary>중섭 게임 실행 파일과 官服 런처(PCLauncher.exe)의 코드 서명자</summary>
        private const string CnSigner = "上海散爆信息技术有限公司";

        /// <summary>B服 런처(빌리빌리 PC 게임 클라이언트, 원래 이름 PCGameClient.exe)의 서명자와 제품명</summary>
        private const string BiliSigner = "上海宽娱数码科技有限公司";
        private const string BiliLauncherProduct = "少女前线2：追放 启动器";

        /// <returns>(중섭인지, 채널 "官服"/"B服", 설명)</returns>
        public static (bool IsCn, string Channel, string Note) Verify(string gameDir)
        {
            var data = Path.Combine(gameDir, "GF2_Exilium_Data");

            string info;
            try { info = File.ReadAllText(Path.Combine(data, "app.info")).Replace("\r", "").TrimEnd('\n'); }
            catch { return (false, "", "app.info 를 읽을 수 없음"); }
            if (info != CnAppInfo) return (false, "", "제품명이 중섭과 다름: " + info.Replace('\n', ' '));

            if (Signer(Path.Combine(gameDir, "GF2_Exilium.exe")) != CnSigner)
                return (false, "", "게임 실행 파일의 서명자가 중섭 개발사가 아님");

            // 게임이 기록한 런처 경로 (GameConfig.cfg ClientPath)
            var clientPath = ReadClientPath(Path.Combine(data, "LocalCache", "Data", "GameConfig.cfg"));

            // 官服: 공식 런처 ↔ 게임 폴더 양방향 연결
            if (clientPath != null && File.Exists(clientPath) &&
                string.Equals(Path.GetFileName(clientPath), "PCLauncher.exe", StringComparison.OrdinalIgnoreCase) &&
                Signer(clientPath) == CnSigner)
            {
                var configured = GameLocator.ReadGameInstallPath(Path.Combine(Path.GetDirectoryName(clientPath)!, "config.ini"));
                if (configured == null || !SamePath(configured, gameDir))
                    return (false, "", "官服 런처에 등록된 게임 경로가 이 폴더가 아님");
                return (true, "官服", "제품명·서명·공식 런처 연결 확인");
            }

            // B服: 빌리빌리 런처가 "런처 폴더\Games" 에 게임을 설치한다
            if (FindBiliLauncher(gameDir, clientPath) != null)
                return (true, "B服", "제품명·서명·빌리빌리 런처 확인");

            return (false, "", clientPath == null ? "런처를 확인할 수 없음" : "중섭 런처가 아님 (" + Path.GetFileName(clientPath) + ")");
        }

        private static string? FindBiliLauncher(string gameDir, string? clientPath)
        {
            var candidates = new List<string>();
            if (clientPath != null) candidates.Add(clientPath);
            var dir = Path.GetFullPath(gameDir).TrimEnd('\\');
            if (string.Equals(Path.GetFileName(dir), "Games", StringComparison.OrdinalIgnoreCase))
                candidates.Add(Path.Combine(Path.GetDirectoryName(dir)!, "GF2_Exilium.exe"));

            foreach (var exe in candidates)
            {
                if (!File.Exists(exe) || Signer(exe) != BiliSigner) continue;
                if (FileVersionInfo.GetVersionInfo(exe).ProductName == BiliLauncherProduct) return exe;
            }
            return null;
        }

        /// <summary>서명된 PE 의 서명자 이름. 서버 구분이 목적이라 신뢰 체인 검증은 하지 않는다. 없으면 null.</summary>
        private static string? Signer(string file)
        {
            try
            {
                using var cert = new X509Certificate2(X509Certificate.CreateFromSignedFile(file));
                return cert.GetNameInfo(X509NameType.SimpleName, false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>GameConfig.cfg 의 "ClientPath:C:\...\PCLauncher.exe"</summary>
        private static string? ReadClientPath(string cfg)
        {
            if (!File.Exists(cfg)) return null;
            foreach (var line in File.ReadLines(cfg))
                if (line.StartsWith("ClientPath:", StringComparison.OrdinalIgnoreCase))
                    return line.Substring("ClientPath:".Length).Trim();
            return null;
        }

        private static bool SamePath(string a, string b) =>
            string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
    }
}
