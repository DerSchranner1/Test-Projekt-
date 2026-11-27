using System.Runtime.InteropServices;

namespace RamWaechter.Native;

/// <summary>
/// Leistungsindikatoren über die PDH-API mit englischen Pfaden. Funktioniert damit auch auf
/// deutschem Windows, wo die Indikatoren lokalisierte Namen haben.
/// </summary>
internal sealed class PdhQuery : IDisposable
{
    private const uint PdhFmtDouble = 0x00000200;
    private const uint PdhFmtNoCap100 = 0x00008000;
    private const uint PdhMoreData = 0x800007D2;

    [StructLayout(LayoutKind.Explicit)]
    private struct PDH_FMT_COUNTERVALUE
    {
        [FieldOffset(0)] public uint CStatus;
        [FieldOffset(8)] public double DoubleValue;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PDH_FMT_COUNTERVALUE_ITEM
    {
        public IntPtr Name;
        public PDH_FMT_COUNTERVALUE Value;
    }

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhOpenQueryW(string? dataSource, IntPtr userData, out IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhAddEnglishCounterW(IntPtr query, string path, IntPtr userData, out IntPtr counter);

    [DllImport("pdh.dll")]
    private static extern uint PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll")]
    private static extern uint PdhGetFormattedCounterValue(IntPtr counter, uint format, out uint type, out PDH_FMT_COUNTERVALUE value);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint bufferSize, out uint itemCount, IntPtr buffer);

    [DllImport("pdh.dll")]
    private static extern uint PdhCloseQuery(IntPtr query);

    private IntPtr _query;
    private readonly Dictionary<string, IntPtr> _counters = new();
    private readonly Dictionary<string, string> _missing = new();

    public PdhQuery()
    {
        if (PdhOpenQueryW(null, IntPtr.Zero, out _query) != 0) _query = IntPtr.Zero;
    }

    public bool Has(string key) => _counters.ContainsKey(key);
    public IReadOnlyCollection<string> Missing => _missing.Values;

    /// <summary>Fügt einen Zähler hinzu. Fehlt er (z. B. Hyper-V noch nicht aktiv), wird er bei <see cref="RetryMissing"/> erneut versucht.</summary>
    public bool Add(string key, string englishPath)
    {
        if (_query == IntPtr.Zero || _counters.ContainsKey(key)) return _counters.ContainsKey(key);
        if (PdhAddEnglishCounterW(_query, englishPath, IntPtr.Zero, out var c) == 0)
        {
            _counters[key] = c;
            _missing.Remove(key);
            return true;
        }
        _missing[key] = englishPath;
        return false;
    }

    public void RetryMissing()
    {
        foreach (var (key, path) in _missing.ToList()) Add(key, path);
    }

    public void Collect()
    {
        if (_query != IntPtr.Zero) PdhCollectQueryData(_query);
    }

    public double? Get(string key)
    {
        if (!_counters.TryGetValue(key, out var c)) return null;
        if (PdhGetFormattedCounterValue(c, PdhFmtDouble | PdhFmtNoCap100, out _, out var v) != 0) return null;
        return v.CStatus <= 1 ? v.DoubleValue : null;
    }

    public List<(string Instance, double Value)> GetArray(string key)
    {
        var result = new List<(string, double)>();
        if (!_counters.TryGetValue(key, out var c)) return result;
        uint size = 0;
        uint status = PdhGetFormattedCounterArrayW(c, PdhFmtDouble | PdhFmtNoCap100, ref size, out uint count, IntPtr.Zero);
        if (status != PdhMoreData || size == 0) return result;
        IntPtr buf = Marshal.AllocHGlobal((int)size);
        try
        {
            if (PdhGetFormattedCounterArrayW(c, PdhFmtDouble | PdhFmtNoCap100, ref size, out count, buf) != 0) return result;
            int itemSize = Marshal.SizeOf<PDH_FMT_COUNTERVALUE_ITEM>();
            for (int i = 0; i < count; i++)
            {
                var item = Marshal.PtrToStructure<PDH_FMT_COUNTERVALUE_ITEM>(buf + i * itemSize);
                if (item.Value.CStatus > 1) continue;
                result.Add((Marshal.PtrToStringUni(item.Name) ?? "", item.Value.DoubleValue));
            }
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
        }
        return result;
    }

    public void Dispose()
    {
        if (_query != IntPtr.Zero) PdhCloseQuery(_query);
        _query = IntPtr.Zero;
    }
}
