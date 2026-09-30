using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace SnqxKR
{
    /// <summary>
    /// Everything(voidtools)이 떠 있으면 그 색인에서 파일 이름으로 찾는다. 관리자 권한도 디스크 훑기도 없이 색인된 모든 드라이브를 본다.
    /// Everything IPC (SDK 의 everything_ipc.h, 1.4 방식): Everything 창에 WM_COPYDATA 로 EVERYTHING_IPC_QUERYW 를 보내면
    /// 적어 둔 우리 창으로 WM_COPYDATA(EVERYTHING_IPC_LISTW) 답이 온다. 답은 메시지로 오므로 메시지를 받는 창과 루프를 따로 둔다.
    /// Everything 이 없거나, 관리자 권한으로 떠 있어 메시지가 막히거나(UIPI), 답이 늦으면 빈 목록이다 (다른 방법으로 찾는다).
    /// </summary>
    public static class EverythingSearch
    {
        // 1.4 와 1.5 알파(기본 인스턴스)의 IPC 창
        private static readonly string[] WindowClasses = { "EVERYTHING_TASKBAR_NOTIFICATION", "EVERYTHING_TASKBAR_NOTIFICATION_(1.5a)" };
        private const int CopyDataQueryW = 2;          // EVERYTHING_IPC_COPYDATAQUERYW
        private const uint ReplyId = 0x534E5158;       // 우리 답을 가리는 dwData ("SNQX")
        private const int MaxResults = 1000;
        private const int IpcFolder = 1, IpcDrive = 2; // EVERYTHING_IPC_FOLDER, EVERYTHING_IPC_DRIVE

        /// <summary>
        /// names 와 파일 이름이 똑같은(대소문자 무시) 파일의 전체 경로. status 는 기록에 남길 한 줄.
        /// 메시지 루프가 필요해 전용 스레드에서 묻는다 (부르는 쪽 스레드는 상관없다).
        /// </summary>
        public static List<string> Find(IReadOnlyList<string> names, int timeoutMs, out string status)
        {
            var found = new List<string>();
            string st = "";
            var th = new Thread(() =>
            {
                try { found = Query(names, timeoutMs, out st); }
                catch (Exception e) { st = "Everything 검색 실패: " + e.Message; }
            }) { IsBackground = true };
            th.Start();
            th.Join();
            status = st;
            return found;
        }

        private static List<string> Query(IReadOnlyList<string> names, int timeoutMs, out string status)
        {
            var result = new List<string>();
            IntPtr everything = IntPtr.Zero;
            foreach (var cls in WindowClasses)
                if ((everything = FindWindow(cls, null)) != IntPtr.Zero) break;
            if (everything == IntPtr.Zero)
            {
                status = "Everything 이 떠 있지 않음";
                return result;
            }

            bool replied = false;
            WndProc proc = (hwnd, msg, wParam, lParam) =>
            {
                if (msg == WmCopyData)
                {
                    var cds = (CopyDataStruct)Marshal.PtrToStructure(lParam, typeof(CopyDataStruct));
                    if (cds.dwData == (IntPtr)ReplyId)
                    {
                        // 답의 메모리는 이 호출 동안만 유효하다
                        Parse(cds.lpData, cds.cbData, names, result);
                        replied = true;
                        return (IntPtr)1;
                    }
                }
                return DefWindowProc(hwnd, msg, wParam, lParam);
            };
            string className = "SnqxKR.Everything." + Guid.NewGuid().ToString("N");
            var wc = new WndClassEx
            {
                cbSize = Marshal.SizeOf(typeof(WndClassEx)),
                lpfnWndProc = proc,
                hInstance = GetModuleHandle(null),
                lpszClassName = className,
            };
            if (RegisterClassEx(ref wc) == 0) throw new Win32Exception();
            IntPtr window = CreateWindowEx(0, className, "", 0, 0, 0, 0, 0, HwndMessage, IntPtr.Zero, wc.hInstance, IntPtr.Zero);
            try
            {
                if (window == IntPtr.Zero) throw new Win32Exception();
                // EVERYTHING_IPC_QUERYW: reply_hwnd, reply_copydata_message, search_flags, offset, max_results (DWORD 5개) + 검색어 (UTF-16, NUL)
                // 검색어는 이름들을 | 로 잇고(또는), 정확히 같은 이름만 아래에서 고른다 (wfn: 등 버전마다 다른 문법을 쓰지 않는다)
                string search = string.Join("|", names);
                var query = new byte[20 + (search.Length + 1) * 2];
                BitConverter.GetBytes((uint)window.ToInt64()).CopyTo(query, 0); // SDK 도 창 핸들을 32비트로 넣는다
                BitConverter.GetBytes(ReplyId).CopyTo(query, 4);
                BitConverter.GetBytes((uint)MaxResults).CopyTo(query, 16);
                Encoding.Unicode.GetBytes(search).CopyTo(query, 20);
                var pin = GCHandle.Alloc(query, GCHandleType.Pinned);
                try
                {
                    var cds = new CopyDataStruct { dwData = (IntPtr)CopyDataQueryW, cbData = query.Length, lpData = pin.AddrOfPinnedObject() };
                    if (SendMessageTimeout(everything, WmCopyData, window, ref cds, SmtoAbortIfHung, (uint)timeoutMs, out var accepted) == IntPtr.Zero ||
                        accepted == IntPtr.Zero)
                    {
                        status = "Everything 이 검색을 받지 않음 (Everything 이 관리자 권한으로 떠 있으면 막힌다)";
                        return result;
                    }
                }
                finally
                {
                    pin.Free();
                }

                var sw = Stopwatch.StartNew();
                while (!replied)
                {
                    int left = timeoutMs - (int)sw.ElapsedMilliseconds;
                    if (left <= 0) break;
                    MsgWaitForMultipleObjects(0, IntPtr.Zero, false, (uint)left, QsAllInput);
                    while (PeekMessage(out var m, IntPtr.Zero, 0, 0, PmRemove))
                    {
                        TranslateMessage(ref m);
                        DispatchMessage(ref m);
                    }
                }
                status = replied ? $"Everything 색인에서 {result.Count}개 찾음 ({sw.ElapsedMilliseconds} ms)" : "Everything 응답 없음";
                return result;
            }
            finally
            {
                if (window != IntPtr.Zero) DestroyWindow(window);
                UnregisterClass(className, wc.hInstance);
                GC.KeepAlive(proc);
            }
        }

        /// <summary>
        /// EVERYTHING_IPC_LISTW: totfolders, totfiles, totitems, numfolders, numfiles, numitems, offset (DWORD 7개)
        /// + 항목마다 { flags, filename_offset, path_offset } (DWORD 3개). 오프셋은 목록 시작 기준, 문자열은 UTF-16 NUL 끝.
        /// </summary>
        private static void Parse(IntPtr list, int size, IReadOnlyList<string> names, List<string> result)
        {
            if (list == IntPtr.Zero || size < 28) return;
            int n = Marshal.ReadInt32(list, 20);
            for (int i = 0; i < n && 28 + (i + 1) * 12 <= size; i++)
            {
                int at = 28 + i * 12;
                int flags = Marshal.ReadInt32(list, at);
                if ((flags & (IpcFolder | IpcDrive)) != 0) continue;
                var name = Str(list, Marshal.ReadInt32(list, at + 4), size);
                var path = Str(list, Marshal.ReadInt32(list, at + 8), size);
                if (name == null || path == null) continue;
                if (!names.Any(x => string.Equals(x, name, StringComparison.OrdinalIgnoreCase))) continue;
                try { result.Add(Path.Combine(path, name)); } catch { }
            }
        }

        /// <summary>목록 안 off 에서 NUL 까지 (목록 끝을 넘지 않는다)</summary>
        private static string? Str(IntPtr list, int off, int size)
        {
            if (off < 28 || off >= size) return null;
            int max = (size - off) / 2;
            var s = Marshal.PtrToStringUni(IntPtr.Add(list, off), max);
            int nul = s.IndexOf('\0');
            return nul >= 0 ? s.Substring(0, nul) : null;
        }

        private const uint WmCopyData = 0x004A;
        private static readonly IntPtr HwndMessage = new IntPtr(-3);
        private const uint SmtoAbortIfHung = 0x0002;
        private const uint PmRemove = 0x0001;
        private const uint QsAllInput = 0x04FF;

        private delegate IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

        [StructLayout(LayoutKind.Sequential)]
        private struct CopyDataStruct
        {
            public IntPtr dwData;
            public int cbData;
            public IntPtr lpData;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct WndClassEx
        {
            public int cbSize;
            public uint style;
            public WndProc lpfnWndProc;
            public int cbClsExtra;
            public int cbWndExtra;
            public IntPtr hInstance;
            public IntPtr hIcon;
            public IntPtr hCursor;
            public IntPtr hbrBackground;
            public string? lpszMenuName;
            public string lpszClassName;
            public IntPtr hIconSm;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct Msg
        {
            public IntPtr hwnd;
            public uint message;
            public IntPtr wParam;
            public IntPtr lParam;
            public uint time;
            public int ptX;
            public int ptY;
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr FindWindow(string cls, string? title);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern ushort RegisterClassEx(ref WndClassEx wc);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern bool UnregisterClass(string cls, IntPtr instance);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateWindowEx(int exStyle, string cls, string title, int style, int x, int y, int w, int h,
            IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

        [DllImport("user32.dll")]
        private static extern bool DestroyWindow(IntPtr hwnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr DefWindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint msg, IntPtr wParam, ref CopyDataStruct lParam, uint flags, uint timeout, out IntPtr result);

        [DllImport("user32.dll")]
        private static extern uint MsgWaitForMultipleObjects(uint count, IntPtr handles, bool waitAll, uint milliseconds, uint wakeMask);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern bool PeekMessage(out Msg msg, IntPtr hwnd, uint min, uint max, uint remove);

        [DllImport("user32.dll")]
        private static extern bool TranslateMessage(ref Msg msg);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr DispatchMessage(ref Msg msg);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr GetModuleHandle(string? name);
    }
}
