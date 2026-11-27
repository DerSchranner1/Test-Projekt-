namespace RamWaechter.Core;

/// <summary>Verlauf der Systemwerte dieser Sitzung (Ringpuffer) und Kennzahlen daraus.</summary>
public sealed class History
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(30);
    private readonly List<SystemSample> _samples = new();

    public DateTime? Start { get; private set; }
    public SystemSample? PeakInUse { get; private set; }
    public SystemSample? PeakDemand { get; private set; }
    public SystemSample? PeakCommit { get; private set; }
    public TimeSpan Measured { get; private set; }
    public TimeSpan PressureTime { get; private set; }

    public IReadOnlyList<SystemSample> Samples => _samples;

    public void Add(SystemSample s)
    {
        if (_samples.Count > 0)
        {
            var dt = s.Time - _samples[^1].Time;
            if (dt > TimeSpan.Zero && dt < TimeSpan.FromMinutes(1))
            {
                Measured += dt;
                if (IsPressure(s)) PressureTime += dt;
            }
        }
        Start ??= s.Time;
        _samples.Add(s);
        int drop = _samples.FindIndex(x => s.Time - x.Time <= Window);
        if (drop > 0) _samples.RemoveRange(0, drop);

        if (PeakInUse is null || s.InUse > PeakInUse.InUse) PeakInUse = s;
        if (PeakDemand is null || s.Demand > PeakDemand.Demand) PeakDemand = s;
        if (PeakCommit is null || s.Committed > PeakCommit.Committed) PeakCommit = s;
    }

    public double PressureFraction => Measured.TotalSeconds <= 0 ? 0 : PressureTime.TotalSeconds / Measured.TotalSeconds;

    /// <summary>
    /// RAM-Mangel, der bremst: wenig verfügbar und gleichzeitig viel von der Platte nachgeladen,
    /// oder die Zusagegrenze fast erreicht.
    /// </summary>
    public static bool IsPressure(SystemSample s) =>
        (s.AvailableFraction < 0.10 && s.PagesInputPerSec > 100) || s.CommitFraction > 0.90;

    public double Average(TimeSpan window, Func<SystemSample, double> selector)
    {
        if (_samples.Count == 0) return 0;
        var since = _samples[^1].Time - window;
        var recent = _samples.Where(x => x.Time >= since).Select(selector).Where(v => !double.IsNaN(v)).ToList();
        return recent.Count == 0 ? double.NaN : recent.Average();
    }

    public double Max(TimeSpan window, Func<SystemSample, double> selector)
    {
        if (_samples.Count == 0) return 0;
        var since = _samples[^1].Time - window;
        return _samples.Where(x => x.Time >= since).Select(selector).DefaultIfEmpty(0).Max();
    }
}
