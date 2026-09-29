using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;

namespace SnqxKR
{
    /// <summary>시스템 밝은/어두운 모드를 따라 색을 고른다 (.NET Framework WPF 에는 Fluent 테마가 없어 직접 칠한다)</summary>
    public static class Theme
    {
        public static bool IsDark =>
            Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) is int v && v == 0;

        // 키, 어두운 색, 밝은 색
        private static readonly (string Key, string Dark, string Light)[] Palette =
        {
            ("Bg", "#202020", "#F3F3F3"),
            ("Card", "#2B2B2B", "#FFFFFF"),
            ("CardBorder", "#3A3A3A", "#E0E0E0"),
            ("Text", "#FFFFFF", "#1A1A1A"),
            ("SubText", "#B4B4B4", "#5F5F5F"),
            ("Accent", "#4CC2FF", "#005FB8"),
            ("AccentText", "#000000", "#FFFFFF"),
            ("ButtonBg", "#373737", "#FBFBFB"),
            ("ButtonHover", "#424242", "#F0F0F0"),
            ("ButtonPressed", "#303030", "#E6E6E6"),
            ("ItemBg", "#323232", "#F7F7F7"),
            ("HeroOk", "#1E3A2A", "#DFF6E5"), ("HeroOkText", "#CFF5DC", "#0F5223"),
            ("HeroInfo", "#1D3346", "#E0EEFB"), ("HeroInfoText", "#CDE8FF", "#0B3A66"),
            ("HeroWarn", "#3D3320", "#FFF4CE"), ("HeroWarnText", "#FFE7B3", "#5C4400"),
            ("HeroError", "#46252A", "#FDE7E9"), ("HeroErrorText", "#FFD4D8", "#8A1C28"),
            ("HeroNeutral", "#333438", "#EEEEEE"), ("HeroNeutralText", "#E6E6E6", "#1A1A1A"),
        };

        public static void Apply(Application app)
        {
            bool dark = IsDark;
            foreach (var (key, d, l) in Palette)
            {
                var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(dark ? d : l));
                brush.Freeze();
                app.Resources[key] = brush;
            }
        }

        /// <summary>어두운 모드면 창 제목 표시줄도 어둡게</summary>
        public static void ApplyTitleBar(Window w)
        {
            if (!IsDark) return;
            int on = 1;
            DwmSetWindowAttribute(new WindowInteropHelper(w).Handle, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, ref on, sizeof(int));
        }

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
    }
}
