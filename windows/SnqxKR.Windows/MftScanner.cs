using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace SnqxKR
{
    /// <summary>
    /// NTFS 의 파일 목록(MFT)을 직접 훑어 이름이 맞는 파일을 찾는다 (Everything·goz 와 같은 방식).
    /// 볼륨 핸들이 필요해 관리자 권한에서만 돈다.
    ///
    /// 빠르게 하려고:
    ///  - MFT 를 1MB 씩 읽는다 (시스템 호출 수를 줄임)
    ///  - 레코드마다 문자열을 만들지 않는다. 이름 길이가 찾는 이름과 같을 때만 바이트를 그 자리에서 비교한다
    ///  - 폴더 목록을 들고 있지 않는다. 찾은 파일의 부모 폴더 번호로 OS 에 전체 경로를 한 번에 묻는다 (OpenFileById)
    ///  - 드라이브가 여럿이면 동시에 훑는다
    /// </summary>
    public static class MftScanner
    {
        public sealed class VolumeStats
        {
            public VolumeStats(string drive, long records, long milliseconds, int hits)
            {
                Drive = drive;
                Records = records;
                Milliseconds = milliseconds;
                Hits = hits;
            }

            public string Drive { get; }
            public long Records { get; }
            public long Milliseconds { get; }
            public int Hits { get; }
        }

        public static List<string> Find(string[] names, List<VolumeStats> stats)
        {
            // 비교용: 소문자 UTF-16LE 바이트 (NTFS 이름은 UTF-16)
            var targets = names.Select(n => Encoding.Unicode.GetBytes(n.ToLowerInvariant())).ToArray();
            var drives = DriveInfo.GetDrives()
                .Where(d => { try { return d.DriveType == DriveType.Fixed && d.IsReady && d.DriveFormat == "NTFS"; } catch { return false; } })
                .Select(d => d.Name.Substring(0, 2))
                .ToList();
            var result = new List<string>();
            var gate = new object();
            Parallel.ForEach(drives, drive =>
            {
                try
                {
                    var sw = Stopwatch.StartNew();
                    var (paths, records) = ScanVolume(drive, targets);
                    lock (gate)
                    {
                        result.AddRange(paths);
                        stats.Add(new VolumeStats(drive, records, sw.ElapsedMilliseconds, paths.Count));
                    }
                }
                catch
                {
                    // 한 드라이브가 실패해도 나머지는 본다
                }
            });
            return result;
        }

        private static (List<string> Paths, long Records) ScanVolume(string drive, byte[][] targets)
        {
            using var volume = CreateFile(@"\\.\" + drive, GenericRead, FileShareRead | FileShareWrite, IntPtr.Zero, OpenExisting, 0, IntPtr.Zero);
            if (volume.IsInvalid) throw new Win32Exception();

            var hits = new List<(ulong Parent, string Name)>();
            var med = new MftEnumData { StartFileReferenceNumber = 0, LowUsn = 0, HighUsn = long.MaxValue };
            var buf = new byte[1 << 20];
            long records = 0;
            while (true)
            {
                if (!DeviceIoControl(volume, FsctlEnumUsnData, ref med, Marshal.SizeOf(typeof(MftEnumData)), buf, buf.Length, out var got, IntPtr.Zero))
                {
                    int err = Marshal.GetLastWin32Error();
                    if (err == ErrorHandleEof) break;
                    throw new Win32Exception(err);
                }
                if (got <= 8) break;
                med.StartFileReferenceNumber = BitConverter.ToUInt64(buf, 0);
                for (int off = 8; off < got;)
                {
                    int len = BitConverter.ToInt32(buf, off);
                    if (len <= 0) break;
                    records++;
                    // USN_RECORD_V2: Major@4, Parent@16, FileAttributes@52, FileNameLength@56, FileNameOffset@58
                    if (BitConverter.ToUInt16(buf, off + 4) == 2 &&
                        (BitConverter.ToUInt32(buf, off + 52) & FileAttributeDirectory) == 0)
                    {
                        int nameLen = BitConverter.ToUInt16(buf, off + 56);
                        int nameAt = off + BitConverter.ToUInt16(buf, off + 58);
                        foreach (var t in targets)
                        {
                            if (nameLen != t.Length || !SameNameIgnoreAsciiCase(buf, nameAt, t)) continue;
                            hits.Add((BitConverter.ToUInt64(buf, off + 16), Encoding.Unicode.GetString(buf, nameAt, nameLen)));
                            break;
                        }
                    }
                    off += len;
                }
            }

            // 찾은 파일의 부모 폴더 번호 → 전체 경로 (폴더 목록을 들고 있지 않아도 된다)
            var paths = new List<string>();
            using var root = CreateFile(drive + "\\", 0, FileShareRead | FileShareWrite | FileShareDelete, IntPtr.Zero, OpenExisting, FileFlagBackupSemantics, IntPtr.Zero);
            if (root.IsInvalid) return (paths, records);
            foreach (var (parent, name) in hits)
            {
                var dir = PathOf(root, parent);
                if (dir != null) paths.Add(Path.Combine(dir, name));
            }
            return (paths, records);
        }

        /// <summary>ASCII 대소문자만 무시하고 UTF-16LE 이름을 비교한다 (찾는 이름은 영문)</summary>
        private static bool SameNameIgnoreAsciiCase(byte[] buf, int at, byte[] lower)
        {
            for (int i = 0; i < lower.Length; i += 2)
            {
                int lo = buf[at + i], hi = buf[at + i + 1];
                if (hi != lower[i + 1]) return false;
                if (lo >= 'A' && lo <= 'Z') lo += 32;
                if (lo != lower[i]) return false;
            }
            return true;
        }

        private static string? PathOf(SafeFileHandle volumeHint, ulong fileReference)
        {
            var id = new FileIdDescriptor { Size = Marshal.SizeOf(typeof(FileIdDescriptor)), Type = 0, FileId = (long)fileReference };
            using var h = OpenFileById(volumeHint, ref id, FileReadAttributes, FileShareRead | FileShareWrite | FileShareDelete, IntPtr.Zero, FileFlagBackupSemantics);
            if (h.IsInvalid) return null;
            var sb = new StringBuilder(1024);
            int n = GetFinalPathNameByHandle(h, sb, sb.Capacity, 0);
            if (n <= 0 || n >= sb.Capacity) return null;
            var p = sb.ToString();
            return p.StartsWith(@"\\?\") ? p.Substring(4) : p;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MftEnumData
        {
            public ulong StartFileReferenceNumber;
            public long LowUsn;
            public long HighUsn;
        }

        /// <summary>FILE_ID_DESCRIPTOR (FileIdType). 공용체가 16바이트라 뒤를 채운다.</summary>
        [StructLayout(LayoutKind.Sequential)]
        private struct FileIdDescriptor
        {
            public int Size;
            public int Type;
            public long FileId;
            public long Padding;
        }

        private const uint GenericRead = 0x80000000;
        private const uint FileReadAttributes = 0x80;
        private const uint FileShareRead = 1, FileShareWrite = 2, FileShareDelete = 4, OpenExisting = 3;
        private const uint FileFlagBackupSemantics = 0x02000000;
        private const uint FsctlEnumUsnData = 0x000900b3;
        private const int ErrorHandleEof = 38;
        private const uint FileAttributeDirectory = 0x10;

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern SafeFileHandle CreateFile(string name, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool DeviceIoControl(SafeFileHandle device, uint code, ref MftEnumData input, int inputSize,
            [Out] byte[] output, int outputSize, out int returned, IntPtr overlapped);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern SafeFileHandle OpenFileById(SafeFileHandle volumeHint, ref FileIdDescriptor id, uint access, uint share, IntPtr security, uint flags);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern int GetFinalPathNameByHandle(SafeFileHandle file, StringBuilder path, int size, int flags);
    }
}
