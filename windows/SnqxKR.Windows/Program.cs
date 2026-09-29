using System;

namespace SnqxKR
{
    public static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            // 관리자 권한 도우미(--scan, --copy)는 WPF 를 띄우지 않고 바로 일한다 (UAC 뒤 시작이 빠르다)
            if (args.Length > 0 && args[0].StartsWith("--")) return Elevated.Handle(args);
            var app = new App();
            app.InitializeComponent();
            return app.Run();
        }
    }
}
