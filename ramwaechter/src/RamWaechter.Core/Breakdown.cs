namespace RamWaechter.Core;

public enum Segment
{
    Programme,
    VirtuelleMaschinen,
    SqlGesperrt,
    Komprimiert,
    KernelTreiber,
    Dateicache,
    Geaendert,
    Sonstiges,
    Standby,
    Frei,
    Hardware,
}

public sealed record BreakdownItem(Segment Segment, string Label, ulong Bytes, string Explanation)
{
    /// <summary>Standby und Frei sind sofort verfügbar, der Rest ist „in Verwendung“.</summary>
    public bool IsAvailable => Segment is Segment.Standby or Segment.Frei;
}

/// <summary>Zerlegt den installierten RAM in nachvollziehbare Bereiche (ähnlich RAMMap).</summary>
public static class Breakdown
{
    public static IReadOnlyList<BreakdownItem> Compute(SystemSample s, IReadOnlyList<AppGroup> groups)
    {
        ulong programs = 0, vms = 0, sqlLocked = 0;
        foreach (var g in groups)
        {
            switch (g.Key)
            {
                case AppCatalog.CompressionKey:
                    break; // separat über s.Compressed
                case AppCatalog.WslKey:
                    vms += g.PrivateWs;
                    break;
                case AppCatalog.HyperVKey:
                    programs += g.PrivateWs;
                    vms += g.HiddenBytes;
                    break;
                case AppCatalog.SqlServerKey:
                    programs += g.PrivateWs;
                    sqlLocked += g.HiddenBytes;
                    break;
                default:
                    programs += g.PrivateWs;
                    break;
            }
        }

        ulong kernel = s.NonPagedPool + s.PagedPoolResident + s.DriverResident + s.SystemCodeResident;
        var inUseParts = new (Segment, string, ulong, string)[]
        {
            (Segment.Programme, "Programme", programs,
                "Privater Arbeitsspeicher aller Prozesse, also RAM, den nur der jeweilige Prozess nutzt. Aufschlüsselung im Tab „Apps“."),
            (Segment.VirtuelleMaschinen, "Virtuelle Maschinen", vms,
                "Gast-RAM von Hyper-V-VMs, WSL 2, Docker und Sandbox. Klassische Hyper-V-VMs erscheinen im Task-Manager in keinem Prozess."),
            (Segment.SqlGesperrt, "SQL Server (gesperrt)", sqlLocked,
                "Pufferpool von SQL Server mit „Lock Pages in Memory“. Im Task-Manager unsichtbar."),
            (Segment.Komprimiert, "Komprimiert", s.Compressed,
                "Komprimierte Seiten (Prozess „Memory Compression“). Windows komprimiert lieber, als auf die SSD auszulagern."),
            (Segment.KernelTreiber, "Kernel & Treiber", kernel,
                "Nicht auslagerbarer Pool, residenter auslagerbarer Pool sowie Treiber- und Kernel-Code."),
            (Segment.Dateicache, "Dateicache (aktiv)", s.SystemCacheResident,
                "Aktiv genutzter Dateicache des Systems, z. B. gerade gelesene oder geschriebene Dateien."),
            (Segment.Geaendert, "Geändert", s.Modified,
                "Geänderte Seiten, die erst auf die Platte geschrieben werden müssen, bevor der RAM wieder frei wird."),
        };

        ulong known = 0;
        foreach (var p in inUseParts) known += p.Item3;
        ulong other = s.InUse > known ? s.InUse - known : 0;

        var items = inUseParts.Select(p => new BreakdownItem(p.Item1, p.Item2, p.Item3, p.Item4)).ToList();
        items.Add(new BreakdownItem(Segment.Sonstiges, "Geteilt & Sonstiges", other,
            "Gemeinsam genutzte DLLs und Dateien, Seitentabellen, von Treibern gesperrter Speicher. Diesen Rest ordnet Windows keinem einzelnen Prozess zu."));
        items.Add(new BreakdownItem(Segment.Standby, "Standby (Cache)", s.Standby,
            "Cache mit zuletzt genutzten Daten. Er zählt als verfügbar, Windows gibt ihn sofort her. Viel Standby ist gut, nicht schlecht."));
        items.Add(new BreakdownItem(Segment.Frei, "Frei", s.FreeZero,
            "Komplett ungenutzt. Wenig „Frei“ ist normal, Windows nutzt den Rest als Cache."));
        items.Add(new BreakdownItem(Segment.Hardware, "Hardware-reserviert", s.HardwareReserved,
            "Vom BIOS bzw. der integrierten Grafik reserviert, für Windows nicht nutzbar."));
        return items;
    }

    /// <summary>Summe aller RAM-Werte, die Prozesse als „limitierbare Caches“ halten (SQL, VMs, JVMs …).</summary>
    public static ulong Limitable(IReadOnlyList<AppGroup> groups) =>
        (ulong)groups.Where(g => g.Kind is AppKind.CacheGreedy or AppKind.VirtualMachine).Sum(g => (decimal)g.TotalRam);
}
