using System.Drawing;
using System.Windows.Forms;
using RamWaechter.Core;

namespace RamWaechter.UI;

internal sealed partial class MainForm
{
    private readonly VerdictView _ramView = new("Bremst der RAM gerade?");
    private readonly RamBar _bar = new();
    private readonly MetricsGrid _overviewMetrics = new("Kennzahlen");
    private readonly TopList _top = new();
    private LineChart _usageChart = null!;

    private void BuildOverview()
    {
        _usageChart = new LineChart("Verlauf der letzten 10 Minuten", v => Fmt.Num(v) + " %", 100,
            new ChartSeries("In Verwendung (% RAM)", Theme.SegmentColor(Segment.Programme), s => s.InUseFraction * 100),
            new ChartSeries("Zugesichert (% vom Limit)", Theme.SegmentColor(Segment.Geaendert), s => s.CommitFraction * 100),
            new ChartSeries("Standby (% RAM)", Color.FromArgb(22, 163, 74), s => s.Total == 0 ? 0 : (double)s.Standby / s.Total * 100));

        var stack = new StackPanel { Dock = DockStyle.Fill };
        stack.Add(_ramView);
        stack.Add(_bar);
        stack.Add(_top);
        stack.Add(_overviewMetrics);
        stack.Add(_usageChart);
        _top.GroupClicked += ShowGroup;
        _overviewTab.Controls.Add(stack);
        _overviewTab.BackColor = Theme.Page;
    }

    private void UpdateOverview()
    {
        var s = _sys!;
        if (_ramVerdict is not null) _ramView.Set(_ramVerdict);
        _bar.Set(_breakdown);
        _top.Set(_groups);
        _usageChart.Set(_history.Samples);

        double inputAvg = _history.Average(RamAnalyzer.RecentWindow, x => x.PagesInputPerSec);
        _overviewMetrics.Set(new (string, string, string?)[]
        {
            ("Eingebaut", Fmt.Bytes(s.Installed),
                $"Physisch eingebauter RAM. Für Windows nutzbar: {Fmt.Bytes(s.Total)}. Den Rest reservieren BIOS bzw. integrierte Grafik."),
            ("In Verwendung", $"{Fmt.Bytes(s.InUse)}  ({Fmt.Pct(s.InUseFraction)})",
                "RAM, den Programme, Windows und Treiber gerade wirklich belegen (ohne Standby-Cache)."),
            ("Verfügbar", $"{Fmt.Bytes(s.Available)}  ({Fmt.Pct(s.AvailableFraction)})",
                "Standby + Frei. Das bekommt ein Programm sofort, wenn es RAM anfordert."),
            ("Standby-Cache", Fmt.Bytes(s.Standby),
                "Zuletzt genutzte Daten, die Windows für schnelle Wiederverwendung behält. Wird bei Bedarf sofort freigegeben."),
            ("Frei", Fmt.Bytes(s.FreeZero), "Gar nicht genutzt. Wenig Frei ist normal und gut."),
            ("Komprimiert", Fmt.Bytes(s.Compressed),
                "Physischer RAM, den komprimierte Seiten belegen. Steigt, wenn Windows unter Druck kommt."),
            ("Zugesichert (Commit)", $"{Fmt.Bytes(s.Committed)} / {Fmt.Bytes(s.CommitLimit)}",
                "Allen Programmen zugesagter Speicher. Grenze = RAM + Auslagerungsdatei. Ist sie erreicht, stürzen Programme ab oder Windows muss die Auslagerungsdatei vergrößern."),
            ("Auslagerungsdatei belegt", Fmt.Bytes(_pagefile.Used), "Details im Tab „Auslagerung“."),
            ("Nachladen (60-s-Mittel)", Fmt.Num(inputAvg) + " Seiten/s",
                "Hard Faults: Daten, die nicht im RAM lagen und von der SSD/HDD geholt werden mussten. Dauerhaft hohe Werte bei wenig verfügbarem RAM bremsen."),
            ("Spitze dieser Sitzung", _history.PeakInUse is { } p ? $"{Fmt.Bytes(p.InUse)} um {p.Time:HH:mm}" : "–",
                "Höchster RAM-Verbrauch (ohne Cache), seit RamWächter läuft."),
            ("Zeit unter RAM-Druck", $"{Fmt.Pct(_history.PressureFraction)} von {Fmt.Duration(_history.Measured)}",
                "Anteil der Zeit, in der wenig RAM verfügbar war und gleichzeitig viel nachgeladen wurde (oder das Commit-Limit fast erreicht war)."),
            ("Kernel-Pools", $"{Fmt.Bytes(s.NonPagedPool)} / {Fmt.Bytes(s.PagedPool)}",
                "Nicht auslagerbarer / auslagerbarer Pool von Windows und Treibern. Nicht auslagerbar über ~1–2 GB deutet auf ein Treiberproblem hin."),
        });
    }
}
