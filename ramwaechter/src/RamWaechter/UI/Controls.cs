using System.ComponentModel;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using RamWaechter.Core;

namespace RamWaechter.UI;

internal interface IHeightForWidth
{
    int HeightFor(int width);
}

/// <summary>Stapelt Kinder untereinander in voller Breite und scrollt vertikal.</summary>
internal sealed class StackPanel : Panel
{
    private bool _inLayout;
    public int Gap { get; set; } = 12;

    public StackPanel()
    {
        AutoScroll = true;
        BackColor = Theme.Page;
        DoubleBuffered = true;
    }

    public T Add<T>(T control) where T : Control
    {
        Controls.Add(control);
        PerformLayout();
        return control;
    }

    protected override void OnLayout(LayoutEventArgs e)
    {
        if (_inLayout) return;
        _inLayout = true;
        try
        {
            int pad = (int)Math.Round(16 * DeviceDpi / 96.0), gap = (int)Math.Round(Gap * DeviceDpi / 96.0);
            int width = Math.Max(200, ClientSize.Width - 2 * pad);
            int y = pad + AutoScrollPosition.Y;
            int total = pad;
            foreach (Control c in Controls)
            {
                if (!c.Visible) continue;
                int h = c is IHeightForWidth hw ? hw.HeightFor(width) : c.Height;
                c.SetBounds(pad, y, width, h);
                y += h + gap;
                total += h + gap;
            }
            AutoScrollMinSize = new Size(0, total + pad - gap);
        }
        finally
        {
            _inLayout = false;
        }
    }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        PerformLayout();
    }
}

/// <summary>Grundlage für selbst gezeichnete Karten mit Rahmen.</summary>
internal abstract class CardControl : Control, IHeightForWidth
{
    /// <summary>Rechnet 96-DPI-Pixel in Gerätepixel um (125 %, 150 % … Skalierung).</summary>
    protected int D(int px) => (int)Math.Round(px * DeviceDpi / 96.0);
    protected int Pad => D(16);

    protected CardControl()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        BackColor = Theme.Card;
        ForeColor = Theme.Text;
        Font = Theme.Base;
    }

    public abstract int HeightFor(int width);

    protected void Relayout()
    {
        Parent?.PerformLayout();
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(Parent?.BackColor ?? Theme.Page);
        using var bg = new SolidBrush(BackColor);
        using var pen = new Pen(Theme.Border);
        var r = new Rectangle(0, 0, Width - 1, Height - 1);
        g.FillRectangle(bg, r);
        g.DrawRectangle(pen, r);
        PaintContent(g);
    }

    protected abstract void PaintContent(Graphics g);

    protected override void OnDpiChangedAfterParent(EventArgs e)
    {
        base.OnDpiChangedAfterParent(e);
        Relayout();
    }

    protected static int Measure(string text, Font font, int width) =>
        string.IsNullOrEmpty(text) ? 0 : TextRenderer.MeasureText(text, font, new Size(Math.Max(10, width), int.MaxValue), TextFormatFlags.WordBreak | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix).Height;

    protected static int Draw(Graphics g, string text, Font font, Color color, int x, int y, int width)
    {
        int h = Measure(text, font, width);
        TextRenderer.DrawText(g, text, font, new Rectangle(x, y, width, h + 2), color, TextFormatFlags.WordBreak | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
        return h;
    }
}

/// <summary>Bewertung mit farbigem Streifen: Überschrift, Kurzurteil und Begründungen.</summary>
internal sealed class VerdictView : CardControl
{
    private string _title = "";
    private Verdict _verdict = new(Level.Gut, "Wird gemessen …", Array.Empty<string>());
    private IReadOnlyList<string> _extra = Array.Empty<string>();

    public VerdictView(string title) => _title = title;

    public void Set(Verdict v, IReadOnlyList<string>? extra = null)
    {
        _verdict = v;
        _extra = extra ?? Array.Empty<string>();
        Relayout();
    }

    private IEnumerable<(string Text, Font Font, Color Color)> Lines()
    {
        foreach (var r in _verdict.Reasons) yield return ("•  " + r, Theme.Base, Theme.Text);
        foreach (var r in _extra) yield return (r, Theme.Base, Theme.Text);
    }

    public override int HeightFor(int width)
    {
        int w = width - 2 * Pad - D(8);
        int h = Pad + Measure(_title, Theme.Small, w) + D(4) + Measure(_verdict.Headline, Theme.Headline, w) + D(10);
        foreach (var (t, f, _) in Lines()) h += Measure(t, f, w) + D(6);
        return h + Pad - D(6);
    }

    protected override void PaintContent(Graphics g)
    {
        using (var stripe = new SolidBrush(Theme.LevelColor(_verdict.Level)))
            g.FillRectangle(stripe, 0, 0, D(6), Height);
        int x = Pad + D(8), w = Width - 2 * Pad - D(8), y = Pad;
        y += Draw(g, _title.ToUpperInvariant(), Theme.Small, Theme.Muted, x, y, w) + D(4);
        y += Draw(g, _verdict.Headline, Theme.Headline, Theme.LevelColor(_verdict.Level), x, y, w) + D(10);
        foreach (var (t, f, c) in Lines()) y += Draw(g, t, f, c, x, y, w) + D(6);
    }
}

/// <summary>Freier Text mit Überschrift (Erklärungen „Was ist normal?“).</summary>
internal sealed class TextCard : CardControl
{
    private readonly string _title;
    private IReadOnlyList<string> _paragraphs;

    public TextCard(string title, params string[] paragraphs)
    {
        _title = title;
        _paragraphs = paragraphs;
    }

    public void Set(IReadOnlyList<string> paragraphs)
    {
        _paragraphs = paragraphs;
        Relayout();
    }

    public override int HeightFor(int width)
    {
        int w = width - 2 * Pad;
        int h = Pad + Measure(_title, Theme.Bold, w) + D(8);
        foreach (var p in _paragraphs) h += Measure(p, Theme.Base, w) + D(8);
        return h + Pad - D(8);
    }

    protected override void PaintContent(Graphics g)
    {
        int w = Width - 2 * Pad, y = Pad;
        y += Draw(g, _title, Theme.Bold, Theme.Text, Pad, y, w) + D(8);
        foreach (var p in _paragraphs) y += Draw(g, p, Theme.Base, Theme.Text, Pad, y, w) + D(8);
    }
}

/// <summary>Kennzahlen in Spalten: kleine Beschriftung, darunter der Wert.</summary>
internal sealed class MetricsGrid : CardControl
{
    private readonly string _title;
    private IReadOnlyList<(string Label, string Value, string? Tip)> _items = Array.Empty<(string, string, string?)>();
    private readonly ToolTip _tip = new() { AutoPopDelay = 20000, InitialDelay = 300 };
    private readonly List<(Rectangle Rect, string Tip)> _hits = new();
    private string? _shownTip;
    private int ColWidth => D(230);
    private int RowHeight => D(46);

    public MetricsGrid(string title) => _title = title;

    public void Set(IReadOnlyList<(string Label, string Value, string? Tip)> items)
    {
        bool sameShape = items.Count == _items.Count;
        _items = items;
        if (sameShape) Invalidate(); else Relayout();
    }

    private int Columns(int width) => Math.Max(1, (width - 2 * Pad) / ColWidth);

    public override int HeightFor(int width)
    {
        int rows = (_items.Count + Columns(width) - 1) / Columns(width);
        return Pad + D(22) + rows * RowHeight + Pad - D(8);
    }

    protected override void PaintContent(Graphics g)
    {
        _hits.Clear();
        TextRenderer.DrawText(g, _title, Theme.Bold, new Point(Pad, Pad), Theme.Text, TextFormatFlags.NoPrefix);
        int cols = Columns(Width);
        int colW = (Width - 2 * Pad) / cols;
        for (int i = 0; i < _items.Count; i++)
        {
            var (label, value, tip) = _items[i];
            int x = Pad + (i % cols) * colW, y = Pad + D(22) + (i / cols) * RowHeight;
            var flags = TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix;
            TextRenderer.DrawText(g, label + (tip is null ? "" : "  ⓘ"), Theme.Small, new Rectangle(x, y, colW - D(8), D(18)), Theme.Muted, flags);
            TextRenderer.DrawText(g, value, Theme.Value, new Rectangle(x, y + D(17), colW - D(8), D(24)), Theme.Text, flags);
            if (tip is not null) _hits.Add((new Rectangle(x, y, colW - D(8), RowHeight - D(4)), tip));
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        var hit = _hits.FirstOrDefault(h => h.Rect.Contains(e.Location)).Tip;
        if (hit == _shownTip) return;
        _shownTip = hit;
        if (hit is null) _tip.Hide(this); else _tip.Show(hit, this, e.X + 12, e.Y + 18, 20000);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        _shownTip = null;
        _tip.Hide(this);
    }
}

/// <summary>Gestapelter Balken „Wohin geht der RAM?“ mit Legende und Erklärungen als Tooltip.</summary>
internal sealed class RamBar : CardControl
{
    private IReadOnlyList<BreakdownItem> _items = Array.Empty<BreakdownItem>();
    private ulong _total;
    private readonly ToolTip _tip = new() { AutoPopDelay = 30000, InitialDelay = 200 };
    private readonly List<(Rectangle Rect, BreakdownItem Item)> _hits = new();
    private BreakdownItem? _shown;
    private int BarHeight => D(30);
    private int LegendCol => D(250);
    private int LegendRow => D(24);

    public void Set(IReadOnlyList<BreakdownItem> items)
    {
        bool reshape = items.Count(i => i.Bytes > 0) != _items.Count(i => i.Bytes > 0);
        _items = items;
        _total = (ulong)items.Sum(i => (decimal)i.Bytes);
        if (reshape) Relayout(); else Invalidate();
    }

    private IEnumerable<BreakdownItem> NonEmpty => _items.Where(i => i.Bytes > 0);

    private int Cols(int width) => Math.Max(1, (width - 2 * Pad) / LegendCol);

    public override int HeightFor(int width)
    {
        int n = NonEmpty.Count();
        int rows = (n + Cols(width) - 1) / Cols(width);
        return Pad + D(40) + BarHeight + D(14) + rows * LegendRow + Pad;
    }

    protected override void PaintContent(Graphics g)
    {
        _hits.Clear();
        int w = Width - 2 * Pad;
        TextRenderer.DrawText(g, "Wohin geht der RAM?", Theme.Bold, new Point(Pad, Pad), Theme.Text, TextFormatFlags.NoPrefix);
        TextRenderer.DrawText(g, "Maus über einen Bereich: Erklärung", Theme.Small, new Rectangle(Pad, Pad, w, D(18)), Theme.Muted, TextFormatFlags.Right | TextFormatFlags.NoPrefix);
        if (_total == 0) return;

        int barY = Pad + D(40);
        double x = Pad;
        ulong inUse = 0;
        foreach (var it in NonEmpty) if (!it.IsAvailable && it.Segment != Segment.Hardware) inUse += it.Bytes;

        // Markierung „In Verwendung | Verfügbar“
        double inUseWidth = (double)inUse / _total * w;
        TextRenderer.DrawText(g, $"In Verwendung {Fmt.Bytes(inUse)}", Theme.Small, new Point(Pad, barY - D(18)), Theme.Text, TextFormatFlags.NoPrefix);
        string availLabel = $"Verfügbar {Fmt.Bytes(NonEmpty.Where(i => i.IsAvailable).Aggregate(0UL, (s, i) => s + i.Bytes))}";
        var availSize = TextRenderer.MeasureText(availLabel, Theme.Small, Size.Empty, TextFormatFlags.NoPrefix);
        int availX = Math.Min(Pad + (int)inUseWidth + D(6), Width - Pad - availSize.Width);
        TextRenderer.DrawText(g, availLabel, Theme.Small, new Point(availX, barY - D(18)), Theme.Text, TextFormatFlags.NoPrefix);

        g.SmoothingMode = SmoothingMode.None;
        foreach (var it in NonEmpty)
        {
            double segW = (double)it.Bytes / _total * w;
            var rect = new Rectangle((int)Math.Round(x), barY, Math.Max(1, (int)Math.Round(x + segW) - (int)Math.Round(x)), BarHeight);
            using (var b = new SolidBrush(Theme.SegmentColor(it.Segment))) g.FillRectangle(b, rect);
            if (it.Segment == Segment.Hardware)
                using (var hb = new HatchBrush(HatchStyle.BackwardDiagonal, Color.FromArgb(150, 150, 160), Color.Transparent)) g.FillRectangle(hb, rect);
            _hits.Add((rect, it));
            x += segW;
        }
        using (var pen = new Pen(Theme.Text, D(2))) g.DrawLine(pen, Pad + (int)inUseWidth, barY - D(4), Pad + (int)inUseWidth, barY + BarHeight + D(4));

        int cols = Cols(Width), colW = w / cols, i = 0;
        int ly = barY + BarHeight + D(14);
        foreach (var it in NonEmpty)
        {
            int lx = Pad + (i % cols) * colW, yy = ly + (i / cols) * LegendRow;
            using (var b = new SolidBrush(Theme.SegmentColor(it.Segment))) g.FillRectangle(b, lx, yy + D(3), D(12), D(12));
            string label = $"{it.Label}";
            string value = $"{Fmt.Bytes(it.Bytes)}  ({Fmt.Pct((double)it.Bytes / _total)})";
            TextRenderer.DrawText(g, label, Theme.Base, new Rectangle(lx + D(18), yy, colW - D(120), D(20)), Theme.Text, TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
            TextRenderer.DrawText(g, value, Theme.Small, new Rectangle(lx + D(18), yy + D(1), colW - D(26), D(20)), Theme.Muted, TextFormatFlags.Right | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
            _hits.Add((new Rectangle(lx, yy, colW - D(8), LegendRow), it));
            i++;
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        var hit = _hits.FirstOrDefault(h => h.Rect.Contains(e.Location)).Item;
        if (hit == _shown) return;
        _shown = hit;
        if (hit is null) _tip.Hide(this);
        else _tip.Show($"{hit.Label}: {Fmt.Bytes(hit.Bytes)}\n{Wrap(hit.Explanation, 70)}", this, e.X + 12, e.Y + 18, 30000);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        _shown = null;
        _tip.Hide(this);
    }

    internal static string Wrap(string text, int max)
    {
        var lines = new List<string>();
        var line = "";
        foreach (var word in text.Split(' '))
        {
            if (line.Length + word.Length + 1 > max && line.Length > 0)
            {
                lines.Add(line);
                line = word;
            }
            else line = line.Length == 0 ? word : line + " " + word;
        }
        if (line.Length > 0) lines.Add(line);
        return string.Join("\n", lines);
    }
}

internal sealed record ChartSeries(string Name, Color Color, Func<SystemSample, double> Value);

/// <summary>Liniendiagramm über den Verlauf der letzten Minuten.</summary>
internal sealed class LineChart : CardControl
{
    private readonly string _title;
    private readonly IReadOnlyList<ChartSeries> _series;
    private readonly Func<double, string> _format;
    private readonly double? _fixedMax;
    private IReadOnlyList<SystemSample> _samples = Array.Empty<SystemSample>();
    public TimeSpan Span { get; set; } = TimeSpan.FromMinutes(10);

    public LineChart(string title, Func<double, string> format, double? fixedMax, params ChartSeries[] series)
    {
        _title = title;
        _format = format;
        _fixedMax = fixedMax;
        _series = series;
    }

    public void Set(IReadOnlyList<SystemSample> samples)
    {
        _samples = samples;
        Invalidate();
    }

    public override int HeightFor(int width) => D(210);

    protected override void PaintContent(Graphics g)
    {
        TextRenderer.DrawText(g, _title, Theme.Bold, new Point(Pad, Pad), Theme.Text, TextFormatFlags.NoPrefix);
        int lx = Width - Pad;
        foreach (var s in _series.Reverse())
        {
            var size = TextRenderer.MeasureText(s.Name, Theme.Small, Size.Empty, TextFormatFlags.NoPrefix);
            lx -= size.Width;
            TextRenderer.DrawText(g, s.Name, Theme.Small, new Point(lx, Pad + D(2)), Theme.Muted, TextFormatFlags.NoPrefix);
            using var b = new SolidBrush(s.Color);
            g.FillRectangle(b, lx - D(16), Pad + D(8), D(12), D(3));
            lx -= D(28);
        }

        var plot = new Rectangle(Pad + D(56), Pad + D(30), Width - 2 * Pad - D(56), Height - 2 * Pad - D(48));
        if (plot.Width < 20 || plot.Height < 20) return;

        DateTime end = _samples.Count > 0 ? _samples[^1].Time : DateTime.Now;
        DateTime start = end - Span;
        var pts = _samples.Where(s => s.Time >= start).ToList();

        double max = _fixedMax ?? Math.Max(1, _series.SelectMany(s => pts.Select(p => Safe(s.Value(p)))).DefaultIfEmpty(1).Max() * 1.15);
        max = NiceCeiling(max);

        using var gridPen = new Pen(Theme.Grid);
        for (int i = 0; i <= 4; i++)
        {
            int y = plot.Bottom - i * plot.Height / 4;
            g.DrawLine(gridPen, plot.Left, y, plot.Right, y);
            TextRenderer.DrawText(g, _format(max * i / 4), Theme.Small, new Rectangle(Pad, y - D(8), D(52), D(16)), Theme.Muted, TextFormatFlags.Right | TextFormatFlags.NoPrefix);
        }
        TextRenderer.DrawText(g, $"−{(int)Span.TotalMinutes} min", Theme.Small, new Point(plot.Left, plot.Bottom + D(4)), Theme.Muted, TextFormatFlags.NoPrefix);
        TextRenderer.DrawText(g, "jetzt", Theme.Small, new Rectangle(plot.Left, plot.Bottom + D(4), plot.Width, D(16)), Theme.Muted, TextFormatFlags.Right | TextFormatFlags.NoPrefix);

        if (pts.Count < 2) return;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        foreach (var s in _series)
        {
            var poly = pts.Select(p => new PointF(
                plot.Left + (float)((p.Time - start).TotalSeconds / Span.TotalSeconds * plot.Width),
                plot.Bottom - (float)(Math.Min(max, Safe(s.Value(p))) / max * plot.Height))).ToArray();
            using var pen = new Pen(s.Color, D(2));
            g.DrawLines(pen, poly);
        }
    }

    private static double Safe(double v) => double.IsNaN(v) || double.IsInfinity(v) ? 0 : Math.Max(0, v);

    private static double NiceCeiling(double v)
    {
        if (v <= 0) return 1;
        double mag = Math.Pow(10, Math.Floor(Math.Log10(v)));
        foreach (var m in new[] { 1, 2, 2.5, 5, 10 })
            if (m * mag >= v) return m * mag;
        return 10 * mag;
    }
}

/// <summary>Kurze Rangliste der größten Verbraucher mit Balken.</summary>
internal sealed class TopList : CardControl
{
    private IReadOnlyList<AppGroup> _groups = Array.Empty<AppGroup>();
    private readonly List<(Rectangle Rect, AppGroup Group)> _hits = new();
    private const int Count = 8;
    private int Row => D(30);
    public event Action<AppGroup>? GroupClicked;

    public TopList() => Cursor = Cursors.Hand;

    public void Set(IReadOnlyList<AppGroup> groups)
    {
        _groups = groups.Take(Count).ToList();
        Invalidate();
    }

    public override int HeightFor(int width) => Pad + D(24) + Count * Row + Pad - D(6);

    protected override void PaintContent(Graphics g)
    {
        _hits.Clear();
        int w = Width - 2 * Pad;
        TextRenderer.DrawText(g, "Größte Verbraucher", Theme.Bold, new Point(Pad, Pad), Theme.Text, TextFormatFlags.NoPrefix);
        TextRenderer.DrawText(g, "Klick: Details und Tipps", Theme.Small, new Rectangle(Pad, Pad, w, D(18)), Theme.Muted, TextFormatFlags.Right | TextFormatFlags.NoPrefix);
        ulong max = _groups.Count > 0 ? Math.Max(1, _groups.Max(x => x.TotalRam)) : 1;
        int y = Pad + D(24);
        int nameW = Math.Min(D(330), w / 3), valueW = D(90), badgeW = D(190);
        foreach (var grp in _groups)
        {
            var rowRect = new Rectangle(Pad, y, w, Row - D(4));
            using (var b = new SolidBrush(Theme.KindRowColor(grp.Kind, grp.LeakSuspect))) g.FillRectangle(b, rowRect);
            TextRenderer.DrawText(g, grp.Name, Theme.Base, new Rectangle(Pad + D(6), y + D(4), nameW - D(6), D(20)), Theme.Text, TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
            string badge = grp.LeakSuspect ? "⚠ wächst stetig" : grp.Known?.KindLabel ?? "";
            if (grp.Notes.Any(n => n.Contains("kein Limit"))) badge = "⚠ kein Limit gesetzt";
            TextRenderer.DrawText(g, badge, Theme.Small, new Rectangle(Pad + nameW, y + D(5), badgeW, D(18)), Theme.Muted, TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
            int barX = Pad + nameW + badgeW, barW = Math.Max(10, w - nameW - badgeW - valueW - D(8));
            int privW = (int)((double)grp.PrivateWs / max * barW), hidW = (int)((double)grp.HiddenBytes / max * barW);
            using (var b = new SolidBrush(Theme.SegmentColor(Segment.Programme))) g.FillRectangle(b, barX, y + D(8), Math.Max(1, privW), D(10));
            if (hidW > 0)
                using (var b = new SolidBrush(Theme.SegmentColor(grp.Key == AppCatalog.SqlServerKey ? Segment.SqlGesperrt : Segment.VirtuelleMaschinen)))
                    g.FillRectangle(b, barX + privW, y + D(8), hidW, D(10));
            TextRenderer.DrawText(g, Fmt.Bytes(grp.TotalRam), Theme.Bold, new Rectangle(Pad + w - valueW, y + D(4), valueW - D(6), D(20)), Theme.Text, TextFormatFlags.Right | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
            _hits.Add((rowRect, grp));
            y += Row;
        }
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        base.OnMouseClick(e);
        var hit = _hits.FirstOrDefault(h => h.Rect.Contains(e.Location)).Group;
        if (hit is not null) GroupClicked?.Invoke(hit);
    }
}

/// <summary>ListView ohne Flackern bei häufigen Aktualisierungen.</summary>
internal sealed class SmoothListView : ListView
{
    public SmoothListView()
    {
        DoubleBuffered = true;
        View = View.Details;
        FullRowSelect = true;
        HideSelection = false;
        MultiSelect = false;
        Font = Theme.Base;
        BorderStyle = BorderStyle.FixedSingle;
    }

    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public int FixedHeight { get; set; } = 260;
}

/// <summary>Hält eine ListView fester Höhe innerhalb des StackPanels.</summary>
internal sealed class ListCard : Panel, IHeightForWidth
{
    private readonly Label _title;
    public SmoothListView List { get; } = new() { Dock = DockStyle.Fill };
    /// <summary>Höhe der Liste in 96-DPI-Pixeln.</summary>
    public int ListHeight { get; set; } = 260;

    public ListCard(string title, string? hint = null)
    {
        BackColor = Theme.Card;
        Padding = new Padding(1);
        _title = new Label
        {
            Text = hint is null ? title : $"{title}   —   {hint}",
            Dock = DockStyle.Top,
            AutoSize = true,
            UseMnemonic = false,
            Font = Theme.Bold,
            ForeColor = Theme.Text,
            Padding = new Padding(12, 8, 0, 6),
        };
        Controls.Add(List);
        Controls.Add(_title);
    }

    public int HeightFor(int width) => (int)Math.Round(ListHeight * DeviceDpi / 96.0) + _title.PreferredHeight + 2;

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        using var pen = new Pen(Theme.Border);
        e.Graphics.DrawRectangle(pen, 0, 0, Width - 1, Height - 1);
    }
}
