namespace RamWaechter.Core;

public sealed record TrackedProcess(
    ProcessSample Sample,
    double HardFaultsPerSec,
    double GrowthMbPerMin,
    bool LeakSuspect,
    string? ParentName);

/// <summary>
/// Verfolgt Prozesse über die Zeit: Hard Faults pro Sekunde und stetiges Wachstum
/// des privaten Speichers (möglicher Leak).
/// </summary>
public sealed class ProcessTracker
{
    public static readonly TimeSpan HistoryStep = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan HistoryWindow = TimeSpan.FromMinutes(15);
    public static readonly TimeSpan MinLeakSpan = TimeSpan.FromMinutes(5);
    public const double MinLeakGrowthMb = 300;
    public const double MinLeakSlopeMbPerMin = 15;
    public const double MinLeakR2 = 0.8;

    private sealed class State
    {
        public uint LastFaults;
        public DateTime LastTime;
        public DateTime LastHistory = DateTime.MinValue;
        public readonly List<(DateTime Time, double Mb)> History = new();
        public double Slope;
        public bool Leak;
    }

    private Dictionary<(int, long), State> _states = new();

    public IReadOnlyList<TrackedProcess> Update(IReadOnlyList<ProcessSample> processes, DateTime now)
    {
        var next = new Dictionary<(int, long), State>(processes.Count);
        var names = new Dictionary<int, string>(processes.Count);
        foreach (var p in processes) names[p.Pid] = p.Name;

        var result = new List<TrackedProcess>(processes.Count);
        foreach (var p in processes)
        {
            var key = (p.Pid, p.CreateTime);
            double faultsPerSec = 0;
            if (_states.TryGetValue(key, out var st))
            {
                double dt = (now - st.LastTime).TotalSeconds;
                if (dt > 0 && p.HardFaultCount >= st.LastFaults)
                    faultsPerSec = (p.HardFaultCount - st.LastFaults) / dt;
            }
            else
            {
                st = new State();
            }
            st.LastFaults = p.HardFaultCount;
            st.LastTime = now;

            if (now - st.LastHistory >= HistoryStep)
            {
                st.LastHistory = now;
                st.History.Add((now, p.PrivateBytes / Fmt.MiB));
                st.History.RemoveAll(h => now - h.Time > HistoryWindow);
                (st.Slope, st.Leak) = EvaluateGrowth(st.History);
            }

            next[key] = st;
            string? parent = p.ParentPid != 0 && names.TryGetValue(p.ParentPid, out var pn) ? pn : null;
            result.Add(new TrackedProcess(p, faultsPerSec, st.Slope, st.Leak, parent));
        }
        _states = next;
        return result;
    }

    /// <summary>Steigung (MB/min) und Leak-Verdacht aus einer Zeitreihe privater Bytes.</summary>
    public static (double SlopeMbPerMin, bool Leak) EvaluateGrowth(IReadOnlyList<(DateTime Time, double Mb)> history)
    {
        if (history.Count < 3) return (0, false);
        var t0 = history[0].Time;
        var pts = history.Select(h => ((h.Time - t0).TotalMinutes, h.Mb)).ToList();
        var (slope, r2) = Regress(pts);
        var span = history[^1].Time - t0;
        double growth = history[^1].Mb - history.Min(h => h.Mb);
        bool leak = span >= MinLeakSpan && growth >= MinLeakGrowthMb && slope >= MinLeakSlopeMbPerMin && r2 >= MinLeakR2;
        return (slope, leak);
    }

    /// <summary>Lineare Regression: Steigung und Bestimmtheitsmaß R².</summary>
    public static (double Slope, double R2) Regress(IReadOnlyList<(double X, double Y)> pts)
    {
        int n = pts.Count;
        if (n < 2) return (0, 0);
        double mx = pts.Average(p => p.X), my = pts.Average(p => p.Y);
        double sxx = 0, sxy = 0, syy = 0;
        foreach (var (x, y) in pts)
        {
            sxx += (x - mx) * (x - mx);
            sxy += (x - mx) * (y - my);
            syy += (y - my) * (y - my);
        }
        if (sxx == 0) return (0, 0);
        double slope = sxy / sxx;
        double r2 = syy == 0 ? 0 : sxy * sxy / (sxx * syy);
        return (slope, r2);
    }
}
