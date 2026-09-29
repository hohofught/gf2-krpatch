using System;
using System.IO;
using System.Windows;

namespace SnqxKR
{
    public partial class App : Application
    {
        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            DispatcherUnhandledException += (_, a) =>
            {
                Crash(a.Exception);
                a.Handled = true;
            };
            try
            {
                Theme.Apply(this);
                MainWindow = new MainWindow();
                MainWindow.Show();
            }
            catch (Exception ex)
            {
                Crash(ex);
                Shutdown(1);
            }
        }

        /// <summary>포터블 exe 라 콘솔이 없다. 오류는 창으로 보여 주고 임시 폴더에 남긴다.</summary>
        private static void Crash(Exception ex)
        {
            var log = Path.Combine(Path.GetTempPath(), "SnqxKR-crash.log");
            try { File.WriteAllText(log, DateTime.Now + Environment.NewLine + ex); } catch { }
            MessageBox.Show((ex.InnerException ?? ex).Message + "\n\n자세한 내용: " + log, "소전2 한글패치 오류",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
