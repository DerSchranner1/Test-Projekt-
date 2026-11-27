namespace RamWaechter.Core;

/// <summary>Systemweite Speicherwerte zu einem Zeitpunkt. Alle Größen in Bytes.</summary>
public sealed record SystemSample
{
    public DateTime Time { get; init; }

    /// <summary>Physisch eingebaut (laut BIOS).</summary>
    public ulong Installed { get; init; }
    /// <summary>Für Windows nutzbar.</summary>
    public ulong Total { get; init; }
    public ulong Available { get; init; }
    public ulong Standby { get; init; }
    public ulong FreeZero { get; init; }
    public ulong Modified { get; init; }

    public ulong Committed { get; init; }
    public ulong CommitLimit { get; init; }
    public ulong CommitPeak { get; init; }

    public ulong PagedPool { get; init; }
    public ulong PagedPoolResident { get; init; }
    public ulong NonPagedPool { get; init; }
    public ulong SystemCacheResident { get; init; }
    public ulong DriverResident { get; init; }
    public ulong SystemCodeResident { get; init; }

    /// <summary>Physischer RAM des komprimierten Speichers (Prozess „Memory Compression“).</summary>
    public ulong Compressed { get; init; }

    /// <summary>Belegter Platz in allen Auslagerungsdateien.</summary>
    public ulong PagefileUsed { get; init; }

    /// <summary>Seiten pro Sekunde, die wegen Hard Faults von der Platte gelesen werden.</summary>
    public double PagesInputPerSec { get; init; }
    /// <summary>Lesevorgänge pro Sekunde für Hard Faults (mehrere Seiten je Vorgang).</summary>
    public double PageReadsPerSec { get; init; }
    public double PagesOutputPerSec { get; init; }
    public double PageWritesPerSec { get; init; }
    /// <summary>Mittlere Lesezeit der Datenträger in Sekunden, NaN wenn unbekannt.</summary>
    public double DiskSecPerRead { get; init; } = double.NaN;

    public ulong InUse => Total > Available ? Total - Available : 0;
    public ulong HardwareReserved => Installed > Total ? Installed - Total : 0;
    public double InUseFraction => Total == 0 ? 0 : (double)InUse / Total;
    public double AvailableFraction => Total == 0 ? 0 : (double)Available / Total;
    public double CommitFraction => CommitLimit == 0 ? 0 : (double)Committed / CommitLimit;

    /// <summary>
    /// Näherung dafür, wie viel RAM das System gern hätte: was im RAM liegt plus was
    /// ausgelagert wurde.
    /// </summary>
    public ulong Demand => InUse + PagefileUsed;
}

public sealed record ProcessSample
{
    public int Pid { get; init; }
    public int ParentPid { get; init; }
    public long CreateTime { get; init; }
    /// <summary>Name ohne „.exe“.</summary>
    public string Name { get; init; } = "";
    public string? Path { get; init; }
    public string? Description { get; init; }

    /// <summary>Privater Arbeitssatz: RAM, den nur dieser Prozess nutzt (wie im Task-Manager).</summary>
    public ulong PrivateWorkingSet { get; init; }
    public ulong WorkingSet { get; init; }
    /// <summary>Private zugesagte Bytes (Commit).</summary>
    public ulong PrivateBytes { get; init; }
    public ulong PeakWorkingSet { get; init; }
    /// <summary>Hard Faults seit Prozessstart (Seiten von der Platte nachgeladen).</summary>
    public uint HardFaultCount { get; init; }

    /// <summary>Privat zugesagter Speicher, der gerade nicht im RAM liegt (ausgelagert, komprimiert oder nie benutzt).</summary>
    public ulong NotResident => PrivateBytes > PrivateWorkingSet ? PrivateBytes - PrivateWorkingSet : 0;
}

/// <summary>Arbeitsspeicher einer Hyper-V-Partition (VM).</summary>
public sealed record VmSample(string Name, ulong Bytes);

public sealed record SqlSample(string Instance, ulong TotalServerBytes, ulong TargetServerBytes, double? PageLifeExpectancy);

public sealed record PagefileEntry(string Path, ulong AllocatedBytes, ulong CurrentUsageBytes, ulong PeakUsageBytes);

public sealed record PagefileInfo(IReadOnlyList<PagefileEntry> Files, bool AutomaticManaged)
{
    public ulong Allocated => (ulong)Files.Sum(f => (decimal)f.AllocatedBytes);
    public ulong Used => (ulong)Files.Sum(f => (decimal)f.CurrentUsageBytes);
    public ulong Peak => (ulong)Files.Sum(f => (decimal)f.PeakUsageBytes);
    public static PagefileInfo Empty { get; } = new(Array.Empty<PagefileEntry>(), false);
}

public sealed record Snapshot(
    SystemSample System,
    IReadOnlyList<ProcessSample> Processes,
    IReadOnlyList<VmSample> Vms,
    IReadOnlyList<SqlSample> Sql);

public enum Level
{
    Gut,
    Hinweis,
    Warnung,
    Kritisch,
}

public sealed record Verdict(Level Level, string Headline, IReadOnlyList<string> Reasons);
