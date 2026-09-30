using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using SnqxKR.Engine;

namespace SnqxKR
{
    public partial class MainWindow : Window
    {
        /// <summary>목록 한 줄</summary>
        public sealed class GameItem
        {
            public GameItem(GameInstall install, string title, string path, string detail)
            {
                Install = install;
                Title = title;
                Path = path;
                Detail = detail;
            }

            public GameInstall Install { get; }
            public string Title { get; }
            public string Path { get; }
            public string Detail { get; }
            public bool IsCn => Install.IsCn;
        }

        private readonly Store store = new Store();
        private readonly PatchSource source;
        private readonly PatchService service;
        private readonly Dictionary<string, Inspection> inspections = new Dictionary<string, Inspection>(StringComparer.OrdinalIgnoreCase);
        private List<GameInstall> installs = new List<GameInstall>();
        private string patchLayout = "";
        private bool remotePending;
        private bool busy;

        public MainWindow()
        {
            InitializeComponent();
            source = new PatchSource(store);
            service = new PatchService(store, Log);
            SourceInitialized += (_, _) => Theme.ApplyTitleBar(this);
            Loaded += async (_, _) =>
            {
                await LocateAsync();
                await CheckRemoteAsync();
            };
            FooterText.Text = "한패 원본: nemasdf/haguel-baefo (안드로이드 앱과 같음) · 번역 엔진: " + service.EngineName + " · 데이터: " + store.Root;
            if (Engines.FallbackReason != null) Log("네이티브 엔진을 쓸 수 없어 C# 엔진을 씁니다: " + Engines.FallbackReason);
            if (store.MigratedFrom != null) Log("이전 데이터 폴더를 옮겼습니다: " + store.MigratedFrom + " → " + store.Root);
            if (store.MigrationError != null) Log("이전 데이터 폴더를 다 옮기지 못했습니다 (다음 실행 때 다시 시도): " + store.MigrationError);
            RenderStorage();
        }

        private GameInstall? Selected => (GameList.SelectedItem as GameItem)?.Install;

        // ---------------- 찾기 ----------------

        private async Task LocateAsync()
        {
            var sw = Stopwatch.StartNew();
            var previous = Selected?.GameDir ?? store.Get("selectedDir");
            installs = await Task.Run(() => GameLocator.LocateAll(store.KnownGameDirs));
            sw.Stop();
            // 확인된 중섭 설치만 기억한다 (다음에 다시 검증)
            store.KnownGameDirs = installs.Where(i => i.IsCn).Select(i => i.GameDir).ToList();

            inspections.Clear();
            foreach (var g in installs.Where(i => i.IsCn))
            {
                try { inspections[g.GameDir] = await service.InspectAsync(g); }
                catch (Exception e) { Log(g.GameDir + " 확인 실패: " + e.Message); }
            }
            patchLayout = await service.PatchLayoutAsync();

            int cn = installs.Count(i => i.IsCn), other = installs.Count - cn;
            FindText.Text = (cn > 0 ? $"중섭 {cn}개 찾음" : "중섭 설치를 찾지 못했습니다") +
                            (other > 0 ? $" · 중섭이 아닌 설치 {other}개는 선택할 수 없습니다" : "") +
                            $" · 탐색 {sw.ElapsedMilliseconds} ms";
            EmptyText.Visibility = installs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

            var items = installs.OrderByDescending(i => i.IsCn).Select(ToItem).ToList();
            GameList.ItemsSource = items;
            GameList.SelectedItem = items.FirstOrDefault(i => i.IsCn && string.Equals(i.Install.GameDir, previous, StringComparison.OrdinalIgnoreCase))
                                    ?? items.FirstOrDefault(i => i.IsCn);
            Render();
        }

        private GameItem ToItem(GameInstall g)
        {
            if (!g.IsCn) return new GameItem(g, "중섭 아님 · 한글패치 대상이 아닙니다", g.GameDir, g.ServerNote);
            inspections.TryGetValue(g.GameDir, out var info);
            return new GameItem(g, "중섭 " + g.Channel + " · " + ShortStatus(g, info), g.GameDir, "찾은 경로: " + g.Source);
        }

        private string ShortStatus(GameInstall g, Inspection? info)
        {
            if (info == null) return "확인 전";
            if (!Directory.Exists(g.TableDir)) return "게임 데이터 없음";
            bool? match = Match(info);
            return info.Status switch
            {
                PatchStatus.PatchedLatest => "최신 한글패치 적용됨",
                PatchStatus.PatchedOld => "한패 업데이트 있음",
                PatchStatus.Repaired => match == true ? "정식 한패 나옴" : "임시 복구 적용됨",
                PatchStatus.Official => match == false ? "게임 업데이트로 한패가 풀림" : "한글패치 미적용",
                PatchStatus.Foreign => match == false ? "게임 버전과 맞지 않는 한패" : "한글패치 들어 있음",
                PatchStatus.NotPatched => "한글패치 미적용",
                _ => "확인 전",
            };
        }

        /// <summary>
        /// 받아 둔 한패를 이 게임에 넣어도 되는지. 버전이 다르거나, 버전은 같은데 번역이 엉뚱한 자리에 들어간 한패면 false.
        /// (업데이트 직후 올라온 한패가 그랬던 적이 있다: 09-22 판 자리 일치 0%)
        /// </summary>
        private bool? Match(Inspection info) =>
            info.GameLayout.Length == 0 || patchLayout.Length == 0 ? (bool?)null
            : info.GameLayout != patchLayout ? false
            : info.PatchAligned != false;

        private async Task CheckRemoteAsync()
        {
            var remote = await source.RemoteEtagAsync();
            remotePending = remote != null && (store.CachedSha.Length == 0 || remote != store.Etag);
            Render();
        }

        private async void Refresh_Click(object sender, RoutedEventArgs e) => await Run("찾는 중", LocateAsync);

        private async void Pick_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new OpenFileDialog
            {
                Title = "게임 또는 런처 실행 파일을 선택하세요",
                Filter = "게임·런처 (GF2_Exilium.exe, PCLauncher.exe)|GF2_Exilium.exe;PCLauncher.exe",
            };
            if (dlg.ShowDialog(this) != true) return;
            var dir = System.IO.Path.GetDirectoryName(dlg.FileName)!;
            var g = GameLocator.FromPickedFolder(dir);
            if (g == null)
            {
                MessageBox.Show(this, "GF2 게임·런처 폴더가 아닙니다.", Title);
                return;
            }
            if (!g.IsCn)
            {
                MessageBox.Show(this, "중섭 클라이언트가 아니라서 한글패치 대상이 아닙니다.\n" + g.ServerNote, Title);
                return;
            }
            store.KnownGameDirs = store.KnownGameDirs.Append(g.GameDir).ToList();
            store.Set("selectedDir", g.GameDir);
            await Run("찾는 중", LocateAsync);
        }

        /// <summary>관리자 권한으로 이 exe 를 한 번 더 띄워 MFT 로 모든 드라이브를 훑는다</summary>
        private async void Scan_Click(object sender, RoutedEventArgs e)
        {
            await Run("전체 스캔 중 (관리자 권한 창에서 허용하세요)", async () =>
            {
                var result = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "SnqxKR-scan-" + Guid.NewGuid().ToString("N") + ".txt");
                var sw = Stopwatch.StartNew();
                var code = await Task.Run(() => Elevated.Scan(result));
                if (code == null) { Show("관리자 권한 요청을 취소했습니다"); return; }
                if (code != 0 || !File.Exists(result)) { Show("전체 스캔 실패 (" + code + ")"); return; }
                var lines = File.ReadAllLines(result).Where(l => l.Trim().Length > 0).ToList();
                File.Delete(result);
                var total = sw.Elapsed;
                // "#C: 레코드수 ms 찾은수" = 드라이브별 MFT 훑기 결과
                var volumes = lines.Where(l => l.StartsWith("#")).Select(l => l.Substring(1).Split(' ')).Where(p => p.Length == 4).ToList();
                var hits = lines.Where(l => !l.StartsWith("#")).ToList();
                foreach (var v in volumes)
                    Log($"  {v[0]} MFT 레코드 {long.Parse(v[1]):N0}개 · {long.Parse(v[2]) / 1000.0:0.00}초 · 찾음 {v[3]}");
                Log($"전체 스캔: 파일 {hits.Count}개, 관리자 권한 창 포함 {total.TotalSeconds:0.0}초");
                foreach (var h in hits) Log("  " + h);
                // 스캔으로 찾은 폴더는 다음 찾기부터 후보로 쓴다 (중섭으로 확인된 것만 남는다)
                store.KnownGameDirs = store.KnownGameDirs.Concat(hits.Select(h => System.IO.Path.GetDirectoryName(h)!)).ToList();
                var locate = Stopwatch.StartNew();
                await LocateAsync();
                var mft = volumes.Sum(v => long.Parse(v[2])) / 1000.0;
                Show($"전체 스캔 완료 · 게임·런처 파일 {hits.Count}개 · 디스크 훑기 {mft:0.00}초 · 확인 {locate.Elapsed.TotalSeconds:0.0}초");
            });
        }

        private void GameList_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (Selected != null) store.Set("selectedDir", Selected.GameDir);
            Render();
        }

        // ---------------- 선택한 게임 ----------------

        private void Render()
        {
            RenderStorage();
            var g = Selected;
            if (g == null || !inspections.TryGetValue(g.GameDir, out var info))
            {
                DetailCard.Visibility = Visibility.Collapsed;
                return;
            }
            DetailCard.Visibility = Visibility.Visible;
            var t = store.For(g.GameDir);
            bool? match = Match(info);
            bool mismatch = match == false;
            bool tableExists = Directory.Exists(g.TableDir);
            bool canRepair = service.CanRepair(g, info);

            // 상태
            var (kind, title, body) =
                !tableExists ? ("Neutral", "게임 데이터가 아직 없습니다", "게임을 한 번 실행해서 로그인하고 데이터를 받은 뒤 '다시 찾기'를 누르세요")
                : info.Running ? ("Warn", "게임이 실행 중입니다", "게임을 완전히 종료한 뒤 적용하세요")
                : info.Status == PatchStatus.Official && mismatch ? ("Error", "게임 업데이트로 한글패치가 풀렸습니다",
                    canRepair ? "새 한패가 나오기 전까지 임시 복구로 쓸 수 있습니다. 새로 생긴 문장만 중국어로 남습니다"
                              : "받아 둔 한패는 이전 게임 버전용입니다. 새 한패가 올라오면 적용하세요")
                : info.Status == PatchStatus.Repaired && match == true ? ("Info", "정식 한글패치가 나왔습니다", "임시 복구본을 정식 한패로 바꾸세요")
                : info.Status == PatchStatus.Repaired ? ("Info", "임시 복구 적용됨",
                    (t.RepairedCoverage >= 0 ? $"한국어 {t.RepairedCoverage:P1} · " : "") + "정식 한패를 기다리는 중")
                : info.Status == PatchStatus.Foreign && mismatch ? ("Error", "게임 버전과 맞지 않는 한글패치",
                    "게임 업데이트 전 한패가 들어 있어 문장이 엉뚱하게 나올 수 있습니다. 게임 버전에 맞는 한패가 나오면 적용하세요")
                : info.Status == PatchStatus.PatchedLatest ? ("Ok", "최신 한글패치 적용됨",
                    t.AppliedAt > 0 ? "적용 " + Time(t.AppliedAt) : "게임 폴더 파일이 최신 한패와 같습니다")
                : info.Status == PatchStatus.PatchedOld ? ("Warn", "한패 업데이트 있음", "적용을 누르면 최신 한패로 바꿉니다")
                : info.Status == PatchStatus.Official ? ("Neutral", "한글패치 미적용", "한글패치가 아직 적용되지 않았습니다. 아래 버튼 한 번이면 다운로드부터 적용까지 끝납니다")
                : info.Status == PatchStatus.Foreign && store.CachedSha.Length == 0 ? ("Info", "한글패치 들어 있음 · 확인 필요", "최신 한패인지 확인하려면 '업데이트 확인'을 누르세요")
                : info.Status == PatchStatus.Foreign ? ("Warn", "이전 버전 한글패치", "적용을 누르면 최신 한패로 바꿉니다")
                : ("Neutral", "한글패치 미적용", "아래 버튼 한 번이면 다운로드부터 적용까지 끝납니다");
            Hero.Background = (Brush)FindResource("Hero" + kind);
            HeroTitle.Foreground = HeroBody.Foreground = (Brush)FindResource("Hero" + kind + "Text");
            HeroTitle.Text = title;
            HeroBody.Text = body;

            // 주 버튼: 버전이 안 맞으면 옛 한패 적용 대신 임시 복구
            string? primary =
                !tableExists ? null
                : info.Status == PatchStatus.Repaired && !mismatch ? "정식 한패로 교체"
                : mismatch && info.Status != PatchStatus.Repaired && canRepair ? "임시 복구"
                : mismatch ? null
                : "한글패치 적용";
            PrimaryButton.Content = primary ?? "";
            PrimaryButton.Visibility = primary == null ? Visibility.Collapsed : Visibility.Visible;
            PrimaryButton.Tag = primary == "임시 복구" ? "repair" : "apply";
            PrimaryButton.IsEnabled = CheckButton.IsEnabled = !busy;
            RestoreButton.IsEnabled = !busy && File.Exists(t.BackupFile) && info.Status != PatchStatus.Official;
            RefreshButton.IsEnabled = PickButton.IsEnabled = ScanButton.IsEnabled = !busy;
            GameList.IsEnabled = !busy;

            var notes = new List<string>();
            if (info.PatchAligned == false)
                notes.Add($"새로 올라온 한패는 이 게임 버전용이지만 문장 자리가 원문과 맞지 않습니다 (번역 안 된 줄 중 {info.AlignRatio:P0} 일치). " +
                          "한패 관리자가 고칠 때까지 넣지 않고 임시 복구를 씁니다.");
            else if (mismatch && !canRepair && info.Status != PatchStatus.Repaired)
                notes.Add("받아 둔 한패는 이전 게임 버전용이라 넣지 않습니다. 이번 버전 공식 원본과 번역 메모리가 있어야 임시 복구할 수 있습니다.");
            if (remotePending) notes.Add("새 한패가 올라왔습니다. '업데이트 확인'이나 적용을 누르면 받습니다.");
            if (g.GameDir.StartsWith(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), StringComparison.OrdinalIgnoreCase) ||
                g.GameDir.StartsWith(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), StringComparison.OrdinalIgnoreCase))
                notes.Add("Program Files 안에 설치돼 있어 파일을 넣을 때 관리자 권한 창이 뜹니다.");
            NoteText.Text = string.Join("\n", notes);
            NoteText.Visibility = notes.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

            // 상세
            InfoPanel.Children.Clear();
            Row("게임", "중섭 " + g.Channel + " · " + g.GameDir);
            Row("서버 확인", g.ServerNote);
            Row("게임 버전", match switch
            {
                true => "받아 둔 한패가 지금 게임 버전용입니다" + (info.PatchAligned == true ? " (원문과 자리 확인됨)" : ""),
                false when info.PatchAligned == false => "받아 둔 한패는 이 버전용이지만 문장 자리가 원문과 맞지 않습니다",
                false => "받아 둔 한패는 이전 게임 버전용입니다",
                _ => store.CachedSha.Length == 0 ? "한패를 아직 받지 않았습니다" : "확인 전",
            });
            if (info.Exists) Row("게임 폴더 파일", Human(info.Size) + " · " + info.Sha.Substring(0, 12) + "…");
            Row("한패 캐시", File.Exists(store.CacheFile) ? Human(new FileInfo(store.CacheFile).Length) + " · 확인 " + Time(store.CheckedAt) : "없음");
            Row("번역 메모리", File.Exists(store.MemoryFile) ? $"{store.MemorySize:N0}줄 · {Time(store.MemoryAt)}" : "없음 (공식 원본과 같은 버전 한패가 모이면 자동으로 만듭니다)");
            if (mismatch && info.Status != PatchStatus.Repaired && canRepair)
                Row("임시 복구 방법", store.CachedSha != store.MemoryPatchSha && File.Exists(store.CacheFile)
                    ? "번역 메모리로 새 원본을 한국어로 바꾸고, 업데이트 전 한패의 번역도 새 자리로 옮깁니다"
                    : "번역 메모리로 새 원본을 한국어로 바꿉니다");
            if (info.Status == PatchStatus.Repaired) Row("임시 복구", (t.RepairedCoverage >= 0 ? $"한국어 {t.RepairedCoverage:P1} · " : "") + Time(t.RepairedAt));
            Row("원본 백업", File.Exists(t.BackupFile) ? "있음 (처음 적용할 때 게임 폴더에 있던 파일)" : "없음 (처음 적용할 때 만듭니다)");
        }

        private void Row(string label, string value)
        {
            var grid = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(110) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.Children.Add(new TextBlock { Text = label, Foreground = (Brush)FindResource("SubText") });
            var v = new TextBlock { Text = value, TextWrapping = TextWrapping.Wrap };
            Grid.SetColumn(v, 1);
            grid.Children.Add(v);
            InfoPanel.Children.Add(grid);
        }

        // ---------------- 동작 ----------------

        private async void Primary_Click(object sender, RoutedEventArgs e)
        {
            var g = Selected;
            if (g == null) return;
            if ((string)PrimaryButton.Tag == "repair") await Run("임시 복구 중", () => RepairAsync(g));
            else await Run("한글패치 적용 중", () => ApplyAsync(g));
        }

        private async void Check_Click(object sender, RoutedEventArgs e)
        {
            var g = Selected;
            if (g == null) return;
            await Run("업데이트 확인 중", async () =>
            {
                await SyncAsync();
                await ReinspectAsync(g);
            });
        }

        private async void Restore_Click(object sender, RoutedEventArgs e)
        {
            var g = Selected;
            if (g == null) return;
            if (MessageBox.Show(this, "처음 적용할 때 백업해 둔 원본(중국어)으로 되돌릴까요?", Title, MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            await Run("원본 복원 중", async () =>
            {
                var r = await service.RestoreAsync(g);
                Show(r.Outcome switch
                {
                    Outcome.Done => "원본 파일로 되돌렸습니다",
                    Outcome.NoBackup => "백업 파일이 없습니다",
                    Outcome.GameRunning => "게임이 실행 중입니다. 완전히 종료한 뒤 복원하세요",
                    Outcome.OldVersion => "백업이 이전 게임 버전의 원본이라 되돌리면 문장이 엉뚱해집니다. 복원하지 않았습니다",
                    _ => "복원 실패: " + r.Message,
                });
                await ReinspectAsync(g);
            });
        }

        private async Task ApplyAsync(GameInstall g)
        {
            var sync = await SyncAsync();
            if (sync.Kind == SyncKind.Failed && !File.Exists(store.CacheFile))
            {
                Show("다운로드 실패: " + sync.Message);
                return;
            }
            Progress.IsIndeterminate = true;
            ProgressText.Text = "게임 폴더에 넣는 중";
            var r = await service.ApplyAsync(g);
            // 자리 검사에 걸린 한패: 번역 수정판 등 사용자가 괜찮다고 보면 그래도 넣을 수 있다
            if (r.Outcome == Outcome.Misaligned &&
                MessageBox.Show(this, $"새 한패의 문장 자리가 원문과 맞지 않습니다 (번역 안 된 줄 중 {r.Coverage:P0} 일치).\n" +
                                      "그대로 넣으면 문장이 엉뚱한 곳에 나올 수 있습니다. 그래도 넣을까요?\n\n" +
                                      "(아니요: 넣지 않고 임시 복구를 계속 씁니다)",
                    Title, MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
                r = await service.ApplyAsync(g, force: true);
            Show(r.Outcome switch
            {
                Outcome.Done => "한글패치 적용 완료",
                Outcome.Same => "이미 최신 한글패치가 적용돼 있습니다",
                Outcome.GameRunning => "게임이 실행 중입니다. 완전히 종료한 뒤 적용하세요",
                Outcome.NoPatch => "한패 파일이 없습니다",
                Outcome.VersionMismatch => "이 한패는 아직 새 게임 버전용이 아닙니다. 임시 복구를 쓰세요",
                Outcome.Misaligned => $"새 한패의 문장 자리가 원문과 맞지 않아 넣지 않았습니다 ({r.Coverage:P0} 일치). 임시 복구를 쓰세요",
                _ => "적용 실패: " + r.Message,
            });
            await ReinspectAsync(g);
        }

        private async Task RepairAsync(GameInstall g)
        {
            Progress.IsIndeterminate = true;
            ProgressText.Text = "새 공식 원본을 한국어로 바꾸는 중";
            var r = await service.RepairAsync(g);
            Show(r.Outcome switch
            {
                Outcome.Done => $"임시 복구 완료 · 한국어 {r.Coverage:P1}" + (r.FromPatch > 0 ? $" (옛 한패에서 {r.FromPatch:N0}줄)" : ""),
                Outcome.GameRunning => "게임이 실행 중입니다. 완전히 종료한 뒤 복구하세요",
                Outcome.NoOfficial => "이 게임 버전의 공식 원본을 아직 보관하지 못했습니다",
                Outcome.NoMemory => "번역 메모리가 없습니다",
                _ => "복구 실패: " + r.Message,
            });
            await ReinspectAsync(g);
        }

        // ---------------- 저장 공간 ----------------

        private void RenderStorage()
        {
            var s = service.Storage();
            StorageTotal.Text = "합계 " + Human(s.Total);
            StoragePanel.Children.Clear();
            StorageRow("캐시", "받은 한패·임시 복구본. 필요하면 다시 받거나 만듭니다", s.Cache, "캐시 삭제", ClearCache_Click);
            StorageRow("번역 메모리", (store.MemorySize > 0 && s.Memory > 0 ? $"{store.MemorySize:N0}줄 · " : "") + "최대 500MB, 넘으면 오래된 줄부터 뺍니다",
                s.Memory, "삭제", DeleteMemory_Click);
            StorageRow("공식 원본 보관본", "임시 복구와 번역 메모리 갱신에 씁니다", s.Official, "삭제", DeleteOfficial_Click);
            StorageRow("원본 백업", "원본 복원에 씁니다", s.Backup, "삭제", DeleteBackups_Click);
        }

        private void StorageRow(string title, string desc, long bytes, string action, RoutedEventHandler onClick)
        {
            var grid = new Grid { Margin = new Thickness(0, 4, 0, 4) };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var text = new StackPanel();
            text.Children.Add(new TextBlock { Text = title + "  " + (bytes > 0 ? Human(bytes) : "없음"), FontWeight = FontWeights.SemiBold });
            text.Children.Add(new TextBlock { Text = desc, Foreground = (Brush)FindResource("SubText"), FontSize = 12, TextWrapping = TextWrapping.Wrap });
            grid.Children.Add(text);
            var button = new Button { Content = action, Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, IsEnabled = !busy && bytes > 0 };
            button.Click += onClick;
            Grid.SetColumn(button, 1);
            grid.Children.Add(button);
            StoragePanel.Children.Add(grid);
        }

        private async void ClearCache_Click(object sender, RoutedEventArgs e) =>
            await Run("캐시 삭제 중", async () => Show("캐시 " + Human(await service.ClearCacheAsync()) + "를 지웠습니다"));

        private async void DeleteMemory_Click(object sender, RoutedEventArgs e)
        {
            if (!Confirm("번역 메모리를 지울까요?\n\n게임 업데이트 뒤 새 한패가 나오기 전의 임시 복구를 못 하게 됩니다. " +
                         "게임 원본과 같은 버전 한패를 다시 적용하면 새로 만들어집니다.")) return;
            await Run("번역 메모리 삭제 중", async () => Show("번역 메모리 " + Human(await service.DeleteMemoryAsync()) + "를 지웠습니다"));
        }

        private async void DeleteOfficial_Click(object sender, RoutedEventArgs e)
        {
            if (!Confirm("공식 원본 보관본을 지울까요?\n\n지금 게임 버전으로 임시 복구하거나 번역 메모리를 새 번역으로 갱신할 때 씁니다. " +
                         "게임 파일이 원본(중국어)으로 돌아오면 다시 보관합니다.")) return;
            await Run("공식 원본 보관본 삭제 중", async () =>
            {
                Show("공식 원본 보관본 " + Human(await service.DeleteOfficialAsync()) + "를 지웠습니다");
                if (Selected != null) await ReinspectAsync(Selected);
            });
        }

        private async void DeleteBackups_Click(object sender, RoutedEventArgs e)
        {
            if (!Confirm("원본 백업을 지울까요?\n\n'원본 복원'을 못 하게 됩니다.")) return;
            await Run("원본 백업 삭제 중", async () => Show("원본 백업 " + Human(await service.DeleteBackupsAsync()) + "를 지웠습니다"));
        }

        private void OpenData_Click(object sender, RoutedEventArgs e)
        {
            Directory.CreateDirectory(store.Root);
            Process.Start(new ProcessStartInfo("explorer.exe", "\"" + store.Root + "\"") { UseShellExecute = true });
        }

        private bool Confirm(string message) =>
            MessageBox.Show(this, message, Title, MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes;

        private async Task<SyncResult> SyncAsync()
        {
            ProgressText.Text = "한패 확인 중";
            var r = await source.SyncAsync((done, total) => Dispatcher.Invoke(() =>
            {
                Progress.IsIndeterminate = total <= 0;
                if (total > 0) Progress.Value = 100.0 * done / total;
                ProgressText.Text = "한패 다운로드 중 · " + Human(done) + (total > 0 ? " / " + Human(total) : "");
            }));
            Log(r.Kind switch
            {
                SyncKind.UpToDate => "한패 최신 상태 (" + r.Message + ")",
                SyncKind.Downloaded => "새 한패 받음",
                _ => "한패 확인 실패: " + r.Message,
            });
            if (r.Kind != SyncKind.Failed) remotePending = false;
            patchLayout = await service.PatchLayoutAsync();
            return r;
        }

        private async Task ReinspectAsync(GameInstall g)
        {
            try { inspections[g.GameDir] = await service.InspectAsync(g); }
            catch (Exception e) { Log("확인 실패: " + e.Message); }
            patchLayout = await service.PatchLayoutAsync();
            var dir = g.GameDir;
            var items = installs.OrderByDescending(i => i.IsCn).Select(ToItem).ToList();
            GameList.ItemsSource = items;
            GameList.SelectedItem = items.FirstOrDefault(i => string.Equals(i.Install.GameDir, dir, StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>버튼을 잠그고 진행 표시를 켠 채로 작업한다</summary>
        private async Task Run(string label, Func<Task> work)
        {
            if (busy) return;
            busy = true;
            ResultText.Visibility = Visibility.Collapsed;
            Progress.Visibility = ProgressText.Visibility = Visibility.Visible;
            Progress.IsIndeterminate = true;
            ProgressText.Text = label;
            Render();
            try
            {
                await work();
            }
            catch (Exception e)
            {
                Log("오류: " + e);
                Show("오류: " + e.Message);
            }
            finally
            {
                busy = false;
                Progress.Visibility = ProgressText.Visibility = Visibility.Collapsed;
                Render();
            }
        }

        private void Show(string message)
        {
            Log(message);
            ResultText.Text = message;
            ResultText.Visibility = Visibility.Visible;
        }

        private void Log(string message)
        {
            void Append() => LogBox.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + message + Environment.NewLine);
            if (Dispatcher.CheckAccess()) Append(); else Dispatcher.Invoke(Append);
        }

        private static string Human(long bytes) =>
            bytes < 0 ? "-"
            : bytes < 1024 * 1024 ? $"{bytes / 1024.0:0.0} KB"
            : bytes < 1024L * 1024 * 1024 ? $"{bytes / 1048576.0:0.0} MB"
            : $"{bytes / 1073741824.0:0.00} GB";

        private static string Time(long ms) =>
            ms <= 0 ? "없음" : DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime.ToString("MM/dd HH:mm");
    }
}
