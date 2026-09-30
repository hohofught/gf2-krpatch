#if NATIVE
using System;
using System.ComponentModel;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace SnqxKR.Engine
{
    /// <summary>
    /// C++ 네이티브 엔진 (native/snqx.h). SnqxKR-native.exe 안에 snqx.dll 을 넣어 두고,
    /// 처음 쓸 때 %LOCALAPPDATA%\SnqxKR\native\&lt;해시&gt;\snqx.dll 로 풀어 올린다 (exe 하나로 배포).
    /// Smart App Control 등이 서명 없는 DLL 을 막으면 Load 가 실패하고 C# 엔진을 쓴다.
    /// </summary>
    public sealed class NativeEngine : IEngine
    {
        private const string Dll = "snqx.dll";

        private NativeEngine(string version) { Name = "네이티브 (" + version + ")"; }

        public string Name { get; }

        public static NativeEngine Load()
        {
            if (IntPtr.Size != 8) throw new PlatformNotSupportedException("64비트 윈도우에서만 네이티브 엔진을 씁니다");
            var path = Extract();
            if (LoadLibraryW(path) == IntPtr.Zero)
            {
                int err = Marshal.GetLastWin32Error();
                throw new InvalidOperationException("네이티브 엔진을 올리지 못함 (" + err + ": " + new Win32Exception(err).Message + ")");
            }
            // 같은 이름(snqx.dll)으로 이미 올라와 있어 아래 DllImport 가 이것을 쓴다
            return new NativeEngine(Str(snqx_version()));
        }

        /// <summary>exe 에 넣은 DLL 을 내용 해시 폴더에 풀어 둔다 (이미 있고 내용이 같으면 그대로)</summary>
        private static string Extract()
        {
            byte[] bytes;
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(Dll))
            {
                if (s == null) throw new FileNotFoundException("exe 안에 네이티브 엔진이 없습니다");
                bytes = new byte[s.Length];
                int got = 0;
                while (got < bytes.Length) got += s.Read(bytes, got, bytes.Length - got);
            }
            string tag;
            using (var sha = SHA256.Create()) tag = LangTable.Hex(sha.ComputeHash(bytes)).Substring(0, 16);
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SnqxKR", "native", tag);
            var path = Path.Combine(dir, Dll);
            if (!File.Exists(path) || new FileInfo(path).Length != bytes.Length)
            {
                Directory.CreateDirectory(dir);
                var tmp = path + ".tmp";
                File.WriteAllBytes(tmp, bytes);
                if (File.Exists(path)) File.Delete(path);
                File.Move(tmp, path);
            }
            return path;
        }

        public string LayoutKey(string path)
        {
            var buf = new byte[64];
            Check(snqx_layout_key(Utf8(path), buf, buf.Length));
            return Str(buf);
        }

        public PatchRepair.Alignment Alignment(string official, string patch)
        {
            var r = new int[2];
            Check(snqx_alignment(Utf8(official), Utf8(patch), r));
            return new PatchRepair.Alignment(r[0], r[1]);
        }

        public MemoryBuild BuildMemory(string official, string patch, string? memoryIn, string memoryOut, long maxBytes)
        {
            var r = new long[5];
            Check(snqx_build_memory(Utf8(official), Utf8(patch), memoryIn != null && File.Exists(memoryIn) ? Utf8(memoryIn) : null,
                Utf8(memoryOut), maxBytes, PatchRepair.MinAlignment, r));
            return new MemoryBuild(r[0] == 1, (int)r[1], (int)r[2], new PatchRepair.Alignment((int)r[3], (int)r[4]));
        }

        public RepairOutput Repair(string official, string memory, string? oldPatch, string outPath)
        {
            var r = new int[3];
            var sha = new byte[65];
            Check(snqx_repair(Utf8(official), Utf8(memory), oldPatch != null ? Utf8(oldPatch) : null, Utf8(outPath), r, sha));
            return new RepairOutput(r[0], r[1], r[2], Str(sha));
        }

        private static void Check(int code)
        {
            if (code == 0) return;
            var msg = Str(snqx_last_error());
            if (msg == "메모리 부족") throw new OutOfMemoryException(msg);
            throw new IOException("네이티브 엔진: " + msg);
        }

        private static byte[] Utf8(string s) => Encoding.UTF8.GetBytes(s + "\0");

        private static string Str(byte[] b)
        {
            int n = Array.IndexOf(b, (byte)0);
            return Encoding.UTF8.GetString(b, 0, n < 0 ? b.Length : n);
        }

        private static string Str(IntPtr p)
        {
            int n = 0;
            while (Marshal.ReadByte(p, n) != 0) n++;
            var b = new byte[n];
            Marshal.Copy(p, b, 0, n);
            return Encoding.UTF8.GetString(b);
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryW(string path);

        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)] private static extern IntPtr snqx_version();
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)] private static extern IntPtr snqx_last_error();
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)] private static extern int snqx_layout_key(byte[] path, byte[] output, int outputLen);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)] private static extern int snqx_alignment(byte[] official, byte[] patch, int[] out2);

        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        private static extern int snqx_build_memory(byte[] official, byte[] patch, byte[]? memoryIn, byte[] memoryOut,
            long maxBytes, double minAlignment, long[] out5);

        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        private static extern int snqx_repair(byte[] official, byte[] memory, byte[]? oldPatch, byte[] outPath, int[] out3, byte[] sha65);
    }
}
#endif
