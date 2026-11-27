using System.Drawing;
using System.Windows.Forms;
using RamWaechter.Core;

namespace RamWaechter.UI;

internal sealed partial class MainForm
{
    private readonly VerdictView _pagingView = new("Ist die Auslagerung normal?");
    private readonly MetricsGrid _pagingMetrics = new("Auslagerung im Detail");
    private readonly ListCard _outsideCard = new("Was liegt nicht im RAM?", "privat zugesagter Speicher, der ausgelagert, komprimiert oder nie benutzt ist") { ListHeight = 280 };
    private LineChart _pagingChart = null!;

    private static readonly string[] PagingNormal =
    {
        "Eine belegte Auslagerungsdatei ist normal. Windows schreibt lange nicht genutzte Seiten vorsorglich hinaus, damit mehr RAM für den Dateicache frei ist. Das kostet nichts, solange diese Seiten nicht ständig zurückgeholt werden.",
        "Entscheidend sind die Hard Faults, also wie oft Daten von der Platte nachgeladen werden. Kurze Spitzen beim Programmstart sind normal, denn auch Programmdateien werden so geladen. Dauerhaft hohe Werte bei wenig verfügbarem RAM bedeuten echte Auslagerung: Programme warten auf die SSD.",
        "Faustregel: Mit einer NVMe-SSD sind unter etwa 100 Lesevorgängen/s nicht spürbar. Mehrere hundert pro Sekunde über Minuten machen das System zäh. Mit einer HDD wird es schon ab etwa 30–50/s spürbar.",
        "„Nicht im RAM“ heißt nicht automatisch ausgelagert. Darin steckt auch Speicher, den ein Programm reserviert, aber nie benutzt hat (z. B. Java-Heaps oder SQL Server). Der kostet keinen RAM.",
        "Die Zusagegrenze (Commit-Limit) ist RAM + Auslagerungsdatei. Wird sie erreicht, schlagen Speicheranforderungen fehl und Programme stürzen ab, auch wenn noch RAM frei ist. Deshalb die Auslagerungsdatei nicht abschalten, sondern von Windows verwalten lassen.",
        "Speicherkomprimierung ist die schnelle Vorstufe zum Auslagern. Komprimieren kostet etwas CPU, ist aber viel schneller als ein Zugriff auf die SSD.",
    };

    private void BuildPaging()
    {
        _pagingChart = new LineChart("Auslagerung der letzten 10 Minuten (Seiten pro Sekunde)", v => Fmt.Num(v), null,
            new ChartSeries("Nachladen (Hard Faults)", Theme.LevelColor(Level.Kritisch), s => s.PagesInputPerSec),
            new ChartSeries("Wegschreiben", Theme.SegmentColor(Segment.Geaendert), s => s.PagesOutputPerSec));

        var list = _outsideCard.List;
        foreach (var (t, w, a) in new (string, int, HorizontalAlignment)[]
                 {
                     ("App", 300, HorizontalAlignment.Left),
                     ("Nicht im RAM", 110, HorizontalAlignment.Right),
                     ("Zugesagt", 100, HorizontalAlignment.Right),
                     ("Im RAM (privat)", 120, HorizontalAlignment.Right),
                     ("Anteil nicht im RAM", 140, HorizontalAlignment.Right),
                     ("Hard Faults/s", 110, HorizontalAlignment.Right),
                 })
            list.Columns.Add(t, D(w), a);
        list.DoubleClick += (_, _) =>
        {
            if (list.SelectedItems.Count > 0 && list.SelectedItems[0].Tag is AppGroup g) ShowGroup(g);
        };

        var stack = new StackPanel { Dock = DockStyle.Fill };
        stack.Add(_pagingView);
        stack.Add(_pagingMetrics);
        stack.Add(_pagingChart);
        stack.Add(_outsideCard);
        stack.Add(new TextCard("Was ist normal?", PagingNormal));
        _pagingTab.Controls.Add(stack);
        _pagingTab.BackColor = Theme.Page;
    }

    private void UpdatePaging()
    {
        var s = _sys!;
        if (_pagingVerdict is not null) _pagingView.Set(_pagingVerdict);
        _pagingChart.Set(_history.Samples);

        var stall = PagingAnalyzer.EstimateStall(_history);
        double avgIn = _history.Average(RamAnalyzer.RecentWindow, x => x.PagesInputPerSec);
        double avgOut = _history.Average(RamAnalyzer.RecentWindow, x => x.PagesOutputPerSec);
        string files = _pagefile.Files.Count == 0 ? "keine" : string.Join(", ", _pagefile.Files.Select(f => f.Path));

        _pagingMetrics.Set(new (string, string, string?)[]
        {
            ("Auslagerungsdatei(en)", files, null),
            ("Belegt / Größe", $"{Fmt.Bytes(_pagefile.Used)} / {Fmt.Bytes(_pagefile.Allocated)}",
                "Wie viel der Auslagerungsdatei gerade Daten enthält. Windows vergrößert sie bei Bedarf, wenn sie automatisch verwaltet wird."),
            ("Spitze seit Systemstart", Fmt.Bytes(_pagefile.Peak), null),
            ("Größe verwaltet", _pagefile.Files.Count == 0 ? "–" : _pagefile.AutomaticManaged ? "automatisch (empfohlen)" : "fest eingestellt",
                "sysdm.cpl → Erweitert → Leistung: Einstellungen → Erweitert → Virtueller Arbeitsspeicher"),
            ("Nachladen (60-s-Mittel)", $"{Fmt.Num(avgIn)} Seiten/s",
                "Pages Input/sec: Seiten, die wegen Hard Faults von der Platte gelesen wurden (4 KB je Seite). Enthält auch das Laden von Programmdateien."),
            ("Lesevorgänge (60-s-Mittel)", $"{Fmt.Num(stall.ReadsPerSec)} /s",
                "Page Reads/sec: Anzahl der Plattenzugriffe dafür. Jeder Zugriff liest mehrere Seiten. Dieser Wert bestimmt die Wartezeit."),
            ("Wegschreiben (60-s-Mittel)", $"{Fmt.Num(avgOut)} Seiten/s",
                "Pages Output/sec: geänderte Seiten, die in die Auslagerungsdatei oder in Dateien geschrieben werden, um RAM frei zu machen."),
            ("Datenträger-Lesezeit", stall.LatencyMeasured ? $"{Fmt.Num(stall.LatencySeconds * 1000, "0.00")} ms" : "unbekannt (0,3 ms angenommen)",
                "Mittlere Dauer eines Lesezugriffs aller Datenträger. NVMe-SSD ≈ 0,1–0,5 ms, SATA-SSD ≈ 0,5–1 ms, HDD ≈ 5–15 ms."),
            ("Geschätzte Wartezeit", $"{Fmt.Num(stall.StallMsPerSec)} ms pro Sekunde",
                "Lesevorgänge × Lesezeit: so lange warten Programme pro Sekunde (über alle Threads summiert) auf ausgelagerte Daten."),
            ("Leistungseinbuße", stall.ImpactLabel.Split(' ')[0], stall.ImpactLabel),
            ("Wartet aufs Wegschreiben", Fmt.Bytes(s.Modified),
                "Modified-Liste: geänderte Seiten, die erst auf die Platte müssen, bevor ihr RAM frei wird."),
            ("Komprimiert im RAM", Fmt.Bytes(s.Compressed), "Statt auszulagern komprimiert Windows zuerst. Das ist deutlich schneller."),
        });

        var list = _outsideCard.List;
        list.BeginUpdate();
        try
        {
            list.Items.Clear();
            foreach (var g in _groups.Where(g => g.NotResident > 0).OrderByDescending(g => g.NotResident).Take(30))
            {
                double share = g.PrivateBytes == 0 ? 0 : (double)g.NotResident / g.PrivateBytes;
                var item = new ListViewItem(new[]
                {
                    g.Name,
                    Fmt.Bytes(g.NotResident),
                    Fmt.Bytes(g.PrivateBytes),
                    Fmt.Bytes(g.PrivateWs),
                    Fmt.Pct(share),
                    g.HardFaultsPerSec >= 0.5 ? Fmt.Num(g.HardFaultsPerSec) : "",
                }) { Tag = g, BackColor = g.HardFaultsPerSec >= 50 ? Color.FromArgb(254, 226, 226) : Color.White };
                list.Items.Add(item);
            }
        }
        finally
        {
            list.EndUpdate();
        }
    }
}
