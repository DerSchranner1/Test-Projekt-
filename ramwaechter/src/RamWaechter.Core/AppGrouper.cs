using System.Text.RegularExpressions;

namespace RamWaechter.Core;

/// <summary>Alle Prozesse einer App zusammengefasst, plus RAM, der keinem Prozess gehört (VM-Gast, gesperrte SQL-Seiten).</summary>
public sealed class AppGroup
{
    public required string Key { get; init; }
    public required string Name { get; init; }
    public KnownApp? Known { get; init; }
    public List<TrackedProcess> Processes { get; } = new();

    public ulong PrivateWs { get; set; }
    public ulong PrivateBytes { get; set; }
    public ulong NotResident { get; set; }
    public double HardFaultsPerSec { get; set; }
    public double GrowthMbPerMin { get; set; }
    public bool LeakSuspect { get; set; }

    /// <summary>RAM, der nicht im Arbeitssatz eines Prozesses auftaucht (VM-Gäste, SQL mit „Lock Pages in Memory“).</summary>
    public ulong HiddenBytes { get; set; }
    public string? HiddenLabel { get; set; }

    /// <summary>Konkrete Beobachtungen für diese App (z. B. „kein Limit gesetzt“).</summary>
    public List<string> Notes { get; } = new();

    public ulong TotalRam => PrivateWs + HiddenBytes;
    public AppKind Kind => Known?.Kind ?? AppKind.Normal;
}

public static class AppGrouper
{
    public const ulong LargeUnknownThreshold = 2UL << 30;
    private static readonly Regex GuidLike = new("^[0-9a-f]{8}-[0-9a-f]{4}-", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static List<AppGroup> Build(
        IReadOnlyList<TrackedProcess> processes,
        IReadOnlyList<VmSample> vms,
        IReadOnlyList<SqlSample> sql,
        ulong totalPhysical)
    {
        var groups = new Dictionary<string, AppGroup>(StringComparer.OrdinalIgnoreCase);

        foreach (var tp in processes)
        {
            var p = tp.Sample;
            if (p.Pid == 0) continue; // Leerlaufprozess
            var known = AppCatalog.Match(p.Name);
            string key = known?.Key ?? "proc:" + p.Name.ToLowerInvariant();
            if (!groups.TryGetValue(key, out var g))
            {
                string name = known?.Name ?? (string.IsNullOrWhiteSpace(p.Description) ? p.Name : $"{p.Description} ({p.Name})");
                g = new AppGroup { Key = key, Name = name, Known = known };
                groups[key] = g;
            }
            g.Processes.Add(tp);
            g.PrivateWs += p.PrivateWorkingSet;
            g.PrivateBytes += p.PrivateBytes;
            g.NotResident += p.NotResident;
            g.HardFaultsPerSec += tp.HardFaultsPerSec;
            g.GrowthMbPerMin += tp.GrowthMbPerMin;
            g.LeakSuspect |= tp.LeakSuspect;
        }

        AddVms(groups, vms);
        AddSql(groups, sql, totalPhysical);

        foreach (var g in groups.Values)
        {
            if (g.LeakSuspect)
                g.Notes.Add($"Wächst seit mehreren Minuten stetig um ca. {Fmt.Num(g.GrowthMbPerMin)} MB/min. Mögliches Speicherleck – beobachten, ggf. neu starten.");
            if (g.Known is null && g.TotalRam >= LargeUnknownThreshold)
                g.Notes.Add(AppCatalog.GenericLargeTip);
            if (g.Key == "webview2")
            {
                var parents = g.Processes.Select(p => p.ParentName).Where(n => n is not null && !n.Equals("msedgewebview2", StringComparison.OrdinalIgnoreCase))
                    .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
                if (parents.Count > 0) g.Notes.Add("Gestartet von: " + string.Join(", ", parents));
            }
        }

        return groups.Values.OrderByDescending(g => g.TotalRam).ToList();
    }

    /// <summary>
    /// VMs mit eigenem vmmem-Prozess (WSL, Docker, Sandbox) werden über den Prozess gezählt.
    /// Klassische Hyper-V-VMs haben keinen Prozess und kommen aus den Vid-Partition-Zählern.
    /// </summary>
    public static IEnumerable<VmSample> ClassicVms(IReadOnlyList<VmSample> vms, bool hasVmmemProcess) =>
        vms.Where(v => !v.Name.Equals("_Total", StringComparison.OrdinalIgnoreCase))
           .Where(v => !hasVmmemProcess || !(v.Name.Contains("WSL", StringComparison.OrdinalIgnoreCase) || GuidLike.IsMatch(v.Name)));

    private static void AddVms(Dictionary<string, AppGroup> groups, IReadOnlyList<VmSample> vms)
    {
        bool hasVmmem = groups.ContainsKey(AppCatalog.WslKey);
        var classic = ClassicVms(vms, hasVmmem).Where(v => v.Bytes > 0).OrderByDescending(v => v.Bytes).ToList();
        if (classic.Count == 0) return;

        if (!groups.TryGetValue(AppCatalog.HyperVKey, out var g))
        {
            var known = AppCatalog.Get(AppCatalog.HyperVKey);
            g = new AppGroup { Key = known.Key, Name = known.Name, Known = known };
            groups[known.Key] = g;
        }
        g.HiddenBytes = (ulong)classic.Sum(v => (decimal)v.Bytes);
        g.HiddenLabel = classic.Count == 1 ? "Gast-RAM der VM" : $"Gast-RAM von {classic.Count} VMs";
        foreach (var vm in classic)
            g.Notes.Add($"VM „{vm.Name}“: {Fmt.Bytes(vm.Bytes)}");
    }

    private static void AddSql(Dictionary<string, AppGroup> groups, IReadOnlyList<SqlSample> sql, ulong totalPhysical)
    {
        if (sql.Count == 0) return;
        if (!groups.TryGetValue(AppCatalog.SqlServerKey, out var g))
        {
            var known = AppCatalog.Get(AppCatalog.SqlServerKey);
            g = new AppGroup { Key = known.Key, Name = known.Name, Known = known };
            groups[known.Key] = g;
        }

        ulong sqlTotal = 0;
        foreach (var s in sql)
        {
            sqlTotal += s.TotalServerBytes;
            string ple = s.PageLifeExpectancy is double v ? $", Page Life Expectancy {Fmt.Num(v)} s" : "";
            g.Notes.Add($"Instanz {s.Instance}: belegt {Fmt.Bytes(s.TotalServerBytes)}, Ziel {Fmt.Bytes(s.TargetServerBytes)}{ple}.");
            if (totalPhysical > 0 && s.TargetServerBytes >= totalPhysical * 0.6)
                g.Notes.Add($"Instanz {s.Instance}: Das Ziel liegt bei {Fmt.Pct((double)s.TargetServerBytes / totalPhysical)} des RAM. Vermutlich ist kein Limit („max server memory“) gesetzt. SQL Server darf damit fast den ganzen RAM nehmen.");
            if (s.PageLifeExpectancy is double p && p < 300 && s.TotalServerBytes > 0)
                g.Notes.Add($"Instanz {s.Instance}: Page Life Expectancy unter 300 s. SQL Server selbst hat zu wenig Puffer für die aktuelle Last.");
        }

        // Mit „Lock Pages in Memory“ liegt der Pufferpool außerhalb des Arbeitssatzes.
        if (sqlTotal > g.PrivateWs + (256UL << 20))
        {
            g.HiddenBytes = sqlTotal - g.PrivateWs;
            g.HiddenLabel = "gesperrte Seiten (im Task-Manager unsichtbar)";
            g.Notes.Add($"{Fmt.Bytes(g.HiddenBytes)} gehören SQL Server, erscheinen aber in keinem Task-Manager-Wert. Ursache ist „Lock Pages in Memory“ (AWE). Das ist kein Fehler, macht den Verbrauch aber unsichtbar.");
        }
    }
}
