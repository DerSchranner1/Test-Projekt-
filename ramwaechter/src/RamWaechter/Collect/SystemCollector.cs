using Microsoft.Win32;
using RamWaechter.Core;
using RamWaechter.Native;

namespace RamWaechter.Collect;

/// <summary>Sammelt alle paar Sekunden System-, Prozess-, Hyper-V- und SQL-Server-Werte.</summary>
internal sealed class SystemCollector : IDisposable
{
    private readonly PdhQuery _pdh = new();
    private readonly ProcessEnumerator _processes = new();
    private readonly List<(string Instance, string Prefix)> _sqlInstances = new();
    private DateTime _lastProbe = DateTime.MinValue;
    private readonly ulong _installed;

    public SystemCollector()
    {
        _pdh.Add("avail", @"\Memory\Available Bytes");
        _pdh.Add("standbyReserve", @"\Memory\Standby Cache Reserve Bytes");
        _pdh.Add("standbyNormal", @"\Memory\Standby Cache Normal Priority Bytes");
        _pdh.Add("standbyCore", @"\Memory\Standby Cache Core Bytes");
        _pdh.Add("free", @"\Memory\Free & Zero Page List Bytes");
        _pdh.Add("modified", @"\Memory\Modified Page List Bytes");
        _pdh.Add("cacheResident", @"\Memory\System Cache Resident Bytes");
        _pdh.Add("driverResident", @"\Memory\System Driver Resident Bytes");
        _pdh.Add("codeResident", @"\Memory\System Code Resident Bytes");
        _pdh.Add("pagedResident", @"\Memory\Pool Paged Resident Bytes");
        _pdh.Add("pagesInput", @"\Memory\Pages Input/sec");
        _pdh.Add("pageReads", @"\Memory\Page Reads/sec");
        _pdh.Add("pagesOutput", @"\Memory\Pages Output/sec");
        _pdh.Add("pageWrites", @"\Memory\Page Writes/sec");
        _pdh.Add("diskRead", @"\PhysicalDisk(_Total)\Avg. Disk sec/Read");
        _pdh.Add("vid", @"\Hyper-V VM Vid Partition(*)\Physical Pages Allocated");
        ProbeSql();
        _pdh.Collect();

        _installed = NativeMethods.GetPhysicallyInstalledSystemMemory(out ulong kb) ? kb * 1024 : 0;
    }

    public Snapshot Collect(PagefileInfo pagefile)
    {
        var now = DateTime.Now;
        if (now - _lastProbe > TimeSpan.FromSeconds(60))
        {
            _pdh.RetryMissing();
            ProbeSql();
            _lastProbe = now;
        }
        _pdh.Collect();

        var perf = NativeMethods.GetPerformance();
        ulong page = perf is { } pi ? (ulong)pi.PageSize : 4096;
        ulong P(UIntPtr v) => (ulong)v * page;
        ulong B(string key) => _pdh.Get(key) is double d && d > 0 ? (ulong)d : 0;
        double R(string key) => _pdh.Get(key) ?? 0;

        var processes = _processes.Enumerate();
        ulong compressed = (ulong)processes
            .Where(p => p.Name.Equals("Memory Compression", StringComparison.OrdinalIgnoreCase) || p.Name.Equals("MemCompression", StringComparison.OrdinalIgnoreCase))
            .Sum(p => (decimal)p.WorkingSet);

        ulong total = perf is { } a ? P(a.PhysicalTotal) : 0;
        ulong available = B("avail") is > 0 and var av ? av : perf is { } b ? P(b.PhysicalAvailable) : 0;

        var sample = new SystemSample
        {
            Time = now,
            Installed = _installed > 0 ? Math.Max(_installed, total) : total,
            Total = total,
            Available = available,
            Standby = B("standbyReserve") + B("standbyNormal") + B("standbyCore"),
            FreeZero = B("free"),
            Modified = B("modified"),
            Committed = perf is { } c1 ? P(c1.CommitTotal) : 0,
            CommitLimit = perf is { } c2 ? P(c2.CommitLimit) : 0,
            CommitPeak = perf is { } c3 ? P(c3.CommitPeak) : 0,
            PagedPool = perf is { } k1 ? P(k1.KernelPaged) : 0,
            NonPagedPool = perf is { } k2 ? P(k2.KernelNonpaged) : 0,
            PagedPoolResident = B("pagedResident"),
            SystemCacheResident = B("cacheResident"),
            DriverResident = B("driverResident"),
            SystemCodeResident = B("codeResident"),
            Compressed = compressed,
            PagefileUsed = pagefile.Used,
            PagesInputPerSec = R("pagesInput"),
            PageReadsPerSec = R("pageReads"),
            PagesOutputPerSec = R("pagesOutput"),
            PageWritesPerSec = R("pageWrites"),
            DiskSecPerRead = _pdh.Get("diskRead") ?? double.NaN,
        };

        var vms = _pdh.GetArray("vid").Select(v => new VmSample(v.Instance, (ulong)(v.Value * 4096))).ToList();

        var sql = new List<SqlSample>();
        foreach (var (instance, _) in _sqlInstances)
        {
            double? totalKb = _pdh.Get("sqlTotal:" + instance);
            if (totalKb is null) continue; // Dienst läuft nicht
            sql.Add(new SqlSample(instance,
                (ulong)(totalKb.Value * 1024),
                (ulong)((_pdh.Get("sqlTarget:" + instance) ?? 0) * 1024),
                _pdh.Get("sqlPle:" + instance)));
        }

        return new Snapshot(sample, processes, vms, sql);
    }

    /// <summary>Findet installierte SQL-Server-Instanzen in der Registry und meldet ihre Zähler an.</summary>
    private void ProbeSql()
    {
        try
        {
            using var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
            using var key = hklm.OpenSubKey(@"SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL");
            if (key is null) return;
            foreach (var name in key.GetValueNames())
            {
                if (_sqlInstances.Any(s => s.Instance == name)) continue;
                string prefix = name.Equals("MSSQLSERVER", StringComparison.OrdinalIgnoreCase) ? "SQLServer" : "MSSQL$" + name;
                _sqlInstances.Add((name, prefix));
                _pdh.Add("sqlTotal:" + name, $@"\{prefix}:Memory Manager\Total Server Memory (KB)");
                _pdh.Add("sqlTarget:" + name, $@"\{prefix}:Memory Manager\Target Server Memory (KB)");
                _pdh.Add("sqlPle:" + name, $@"\{prefix}:Buffer Manager\Page life expectancy");
            }
        }
        catch (Exception)
        {
            // Keine Berechtigung oder kein SQL Server installiert.
        }
    }

    public string Diagnose()
    {
        var vids = _pdh.GetArray("vid");
        return $"PDH fehlend: {(_pdh.Missing.Count == 0 ? "keine" : string.Join(", ", _pdh.Missing))}\n" +
               $"SQL-Instanzen: {(_sqlInstances.Count == 0 ? "keine" : string.Join(", ", _sqlInstances.Select(s => s.Instance)))}\n" +
               $"Hyper-V-Partitionen: {vids.Count} ({string.Join(", ", vids.Select(v => v.Instance))})\n" +
               $"Eingebaut laut BIOS: {_installed / (1UL << 20)} MB";
    }

    public void Dispose() => _pdh.Dispose();
}
