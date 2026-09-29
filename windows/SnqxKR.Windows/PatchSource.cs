using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;

namespace SnqxKR
{
    public enum SyncKind { UpToDate, Downloaded, Failed }

    public sealed class SyncResult
    {
        public SyncResult(SyncKind kind, string message)
        {
            Kind = kind;
            Message = message;
        }

        public SyncKind Kind { get; }
        public string Message { get; }
    }

    /// <summary>
    /// 한패 다운로드. 안드로이드 앱과 같은 저장소(nemasdf/haguel-baefo)를 쓰고, 중복 다운로드는 3단으로 막는다.
    ///  1) HEAD 로 ETag 비교  2) GET + If-None-Match → 304  3) 받은 내용의 SHA-256 이 캐시와 같으면 교체 생략
    /// </summary>
    public sealed class PatchSource
    {
        public const string Url =
            "https://raw.githubusercontent.com/nemasdf/haguel-baefo/refs/heads/main/LangPackageTableCnData.bytes";

        private static readonly HttpClient Http = CreateClient();

        private static HttpClient CreateClient()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var c = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
            c.DefaultRequestHeaders.UserAgent.ParseAdd("SnqxKR");
            return c;
        }

        private readonly Store store;

        public PatchSource(Store store) { this.store = store; }

        /// <summary>서버 파일의 ETag 만 묻는다 (본문 0바이트). 실패하면 null.</summary>
        public async Task<string?> RemoteEtagAsync()
        {
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Head, Url);
                using var res = await Http.SendAsync(req).ConfigureAwait(false);
                return res.Headers.ETag?.Tag.Trim('"');
            }
            catch
            {
                return null;
            }
        }

        public async Task<SyncResult> SyncAsync(Action<long, long> progress)
        {
            var cache = store.CacheFile;
            bool haveCache = File.Exists(cache) && store.CachedSha.Length > 0 && Sha256(cache) == store.CachedSha;

            if (haveCache && store.Etag.Length > 0)
            {
                var remote = await RemoteEtagAsync().ConfigureAwait(false);
                if (remote != null && remote == store.Etag)
                {
                    store.CheckedAt = Now();
                    return new SyncResult(SyncKind.UpToDate, "ETag 동일");
                }
            }

            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, Url);
                if (haveCache && store.Etag.Length > 0) req.Headers.TryAddWithoutValidation("If-None-Match", "\"" + store.Etag + "\"");
                using var res = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
                if (res.StatusCode == HttpStatusCode.NotModified)
                {
                    store.CheckedAt = Now();
                    return new SyncResult(SyncKind.UpToDate, "304");
                }
                if (res.StatusCode != HttpStatusCode.OK) return new SyncResult(SyncKind.Failed, "HTTP " + (int)res.StatusCode);

                long total = res.Content.Headers.ContentLength ?? -1;
                var newTag = res.Headers.ETag?.Tag.Trim('"') ?? "";
                Directory.CreateDirectory(Path.GetDirectoryName(cache)!);
                var part = cache + ".part";
                string newSha;
                using (var sha = SHA256.Create())
                using (var input = await res.Content.ReadAsStreamAsync().ConfigureAwait(false))
                using (var output = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                {
                    var buf = new byte[1 << 16];
                    long done = 0, lastReport = 0;
                    int n;
                    while ((n = await input.ReadAsync(buf, 0, buf.Length).ConfigureAwait(false)) > 0)
                    {
                        output.Write(buf, 0, n);
                        sha.TransformBlock(buf, 0, n, null, 0);
                        done += n;
                        if (done - lastReport > 512 * 1024) { lastReport = done; progress(done, total); }
                    }
                    sha.TransformFinalBlock(buf, 0, 0);
                    progress(done, total);
                    newSha = Hex(sha.Hash);
                    if (total > 0 && done != total)
                    {
                        output.Dispose();
                        File.Delete(part);
                        return new SyncResult(SyncKind.Failed, $"크기 불일치 ({done} / {total})");
                    }
                }

                if (haveCache && newSha == store.CachedSha)
                {
                    File.Delete(part);
                    if (newTag.Length > 0) store.Etag = newTag;
                    store.CheckedAt = Now();
                    return new SyncResult(SyncKind.UpToDate, "내용 해시 동일");
                }
                if (File.Exists(cache)) File.Delete(cache);
                File.Move(part, cache);
                store.CachedSha = newSha;
                store.Etag = newTag;
                store.CheckedAt = Now();
                return new SyncResult(SyncKind.Downloaded, "새 한패");
            }
            catch (Exception e)
            {
                return new SyncResult(SyncKind.Failed, e.Message);
            }
        }

        public static string Sha256(string path)
        {
            using var sha = SHA256.Create();
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 16);
            return Hex(sha.ComputeHash(fs));
        }

        private static string Hex(byte[] bytes)
        {
            var sb = new StringBuilder(bytes.Length * 2);
            foreach (var b in bytes) sb.Append(b.ToString("x2"));
            return sb.ToString();
        }

        public static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    }
}
