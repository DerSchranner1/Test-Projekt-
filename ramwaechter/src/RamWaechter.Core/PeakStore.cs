using System.Text.Json;

namespace RamWaechter.Core;

/// <summary>Höchstwerte eines Tages. Dient der Upgrade-Empfehlung über mehrere Sitzungen hinweg.</summary>
public sealed record DayRecord
{
    public DateOnly Date { get; init; }
    public ulong Installed { get; init; }
    public ulong PeakInUse { get; init; }
    public ulong PeakDemand { get; init; }
    public ulong PeakCommit { get; init; }
    public double MeasuredSeconds { get; init; }
    public double PressureSeconds { get; init; }
}

/// <summary>Speichert Tageshöchstwerte der letzten 30 Tage als JSON.</summary>
public sealed class PeakStore
{
    public const int KeepDays = 30;
    private readonly string? _path;
    private readonly Dictionary<DateOnly, DayRecord> _days = new();
    private SystemSample? _last;

    public PeakStore(string? path)
    {
        _path = path;
        if (path is null || !File.Exists(path)) return;
        try
        {
            var list = JsonSerializer.Deserialize<List<DayRecord>>(File.ReadAllText(path));
            if (list is not null)
                foreach (var d in list) _days[d.Date] = d;
        }
        catch (Exception)
        {
            // Beschädigte Datei: neu anfangen.
        }
    }

    public IReadOnlyList<DayRecord> Days => _days.Values.OrderBy(d => d.Date).ToList();

    public void Add(SystemSample s)
    {
        var date = DateOnly.FromDateTime(s.Time);
        _days.TryGetValue(date, out var d);
        d ??= new DayRecord { Date = date };

        double dt = 0;
        if (_last is not null)
        {
            var span = s.Time - _last.Time;
            if (span > TimeSpan.Zero && span < TimeSpan.FromMinutes(1)) dt = span.TotalSeconds;
        }
        _last = s;

        _days[date] = d with
        {
            Installed = Math.Max(d.Installed, s.Installed),
            PeakInUse = Math.Max(d.PeakInUse, s.InUse),
            PeakDemand = Math.Max(d.PeakDemand, s.Demand),
            PeakCommit = Math.Max(d.PeakCommit, s.Committed),
            MeasuredSeconds = d.MeasuredSeconds + dt,
            PressureSeconds = d.PressureSeconds + (History.IsPressure(s) ? dt : 0),
        };

        var cutoff = date.AddDays(-KeepDays);
        foreach (var old in _days.Keys.Where(k => k < cutoff).ToList()) _days.Remove(old);
    }

    /// <summary>Nur Tage mit der aktuellen RAM-Größe zählen; nach einem Upgrade fängt die Statistik neu an.</summary>
    public IEnumerable<DayRecord> ForInstalled(ulong installed) =>
        _days.Values.Where(d => d.Installed == 0 || d.Installed == installed);

    public ulong PeakDemand(ulong installed) => ForInstalled(installed).Select(d => d.PeakDemand).DefaultIfEmpty(0UL).Max();
    public TimeSpan Measured(ulong installed) => TimeSpan.FromSeconds(ForInstalled(installed).Sum(d => d.MeasuredSeconds));

    public double PressureFraction(ulong installed)
    {
        var days = ForInstalled(installed).ToList();
        double m = days.Sum(d => d.MeasuredSeconds);
        return m <= 0 ? 0 : days.Sum(d => d.PressureSeconds) / m;
    }

    public void Save()
    {
        if (_path is null) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            string tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(Days, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, _path, overwrite: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
