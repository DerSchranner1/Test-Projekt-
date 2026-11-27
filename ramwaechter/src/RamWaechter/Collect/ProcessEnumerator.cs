using System.Diagnostics;
using System.Runtime.InteropServices;
using RamWaechter.Core;
using RamWaechter.Core.Interop;
using RamWaechter.Native;

namespace RamWaechter.Collect;

/// <summary>
/// Liest alle Prozesse mit einem einzigen Systemaufruf. Pfade und Beschreibungen werden
/// zwischengespeichert, weil sie sich während der Laufzeit eines Prozesses nicht ändern.
/// </summary>
internal sealed class ProcessEnumerator
{
    private int _bufferSize = 1 << 20;
    private Dictionary<(int, long), (string? Path, string? Description)> _cache = new();
    private readonly Dictionary<string, string?> _descriptions = new(StringComparer.OrdinalIgnoreCase);

    public unsafe List<ProcessSample> Enumerate()
    {
        List<ProcessSample> raw;
        while (true)
        {
            IntPtr buf = Marshal.AllocHGlobal(_bufferSize);
            try
            {
                uint status = NativeMethods.NtQuerySystemInformation(NativeMethods.SystemProcessInformation, buf, _bufferSize, out int needed);
                if (status == NativeMethods.StatusInfoLengthMismatch)
                {
                    _bufferSize = Math.Max(_bufferSize * 2, needed + (64 << 10));
                    continue;
                }
                if (status != 0) return new List<ProcessSample>();
                raw = ProcessInfoParser.Parse((byte*)buf, _bufferSize, (p, len) => new string((char*)p, 0, len));
                break;
            }
            finally
            {
                Marshal.FreeHGlobal(buf);
            }
        }

        var next = new Dictionary<(int, long), (string?, string?)>(raw.Count);
        var result = new List<ProcessSample>(raw.Count);
        foreach (var p in raw)
        {
            var key = (p.Pid, p.CreateTime);
            if (!_cache.TryGetValue(key, out var info))
            {
                string? path = p.Pid > 4 ? NativeMethods.GetProcessPath(p.Pid) : null;
                info = (path, path is null ? null : Describe(path));
            }
            next[key] = info;
            result.Add(p with { Path = info.Path, Description = info.Description });
        }
        _cache = next;
        return result;
    }

    private string? Describe(string path)
    {
        if (_descriptions.TryGetValue(path, out var d)) return d;
        try
        {
            d = FileVersionInfo.GetVersionInfo(path).FileDescription?.Trim();
            if (string.IsNullOrEmpty(d)) d = null;
        }
        catch (Exception)
        {
            d = null;
        }
        _descriptions[path] = d;
        return d;
    }
}
