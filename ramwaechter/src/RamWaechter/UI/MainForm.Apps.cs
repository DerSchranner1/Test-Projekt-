using System.Collections;
using System.Drawing;
using System.Windows.Forms;
using RamWaechter.Core;
using RamWaechter.Native;

namespace RamWaechter.UI;

internal sealed partial class MainForm
{
    private readonly SmoothListView _appList = new() { Dock = DockStyle.Fill, BorderStyle = BorderStyle.None };
    private readonly RichTextBox _appDetail = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        BorderStyle = BorderStyle.None,
        BackColor = Color.White,
        Font = Theme.Base,
        DetectUrls = false,
    };
    private readonly Dictionary<string, ListViewItem> _appItems = new();
    private string? _detailKey;
    private string? _detailText;
    private int _sortColumn = 2;
    private bool _sortDescending = true;
    private SplitContainer _appSplit = null!;

    private static readonly (string Title, int Width, HorizontalAlignment Align)[] AppColumns =
    {
        ("App", 270, HorizontalAlignment.Left),
        ("Art", 170, HorizontalAlignment.Left),
        ("RAM", 90, HorizontalAlignment.Right),
        ("davon unsichtbar", 115, HorizontalAlignment.Right),
        ("Prozesse", 70, HorizontalAlignment.Right),
        ("Zugesagt", 90, HorizontalAlignment.Right),
        ("Nicht im RAM", 100, HorizontalAlignment.Right),
        ("Hard Faults/s", 95, HorizontalAlignment.Right),
        ("Trend MB/min", 95, HorizontalAlignment.Right),
        ("Hinweis", 320, HorizontalAlignment.Left),
    };

    private void BuildApps()
    {
        foreach (var (title, width, align) in AppColumns)
            _appList.Columns.Add(title, D(width), align);
        _appList.ColumnClick += (_, e) =>
        {
            if (_sortColumn == e.Column) _sortDescending = !_sortDescending;
            else
            {
                _sortColumn = e.Column;
                _sortDescending = e.Column >= 2;
            }
            SortApps();
        };
        _appList.SelectedIndexChanged += (_, _) => UpdateAppDetail(force: true);

        var legend = new Label
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            Padding = new Padding(D(12), D(8), D(12), D(8)),
            BackColor = Theme.Card,
            ForeColor = Theme.Muted,
            UseMnemonic = false,
            Text = "Gelb = reserviert RAM absichtlich (lässt sich begrenzen) · Lila = virtuelle Maschine · Rot = wächst stetig (möglicher Leak). Zeile anklicken für Erklärung und Tipps.\n" +
                   "RAM = privater Arbeitsspeicher aller Prozesse der App + RAM, den der Task-Manager nicht zeigt (VM-Gast, gesperrte SQL-Seiten).\n" +
                   "Zugesagt = reservierter privater Speicher · Nicht im RAM = davon ausgelagert, komprimiert oder nie benutzt.",
        };

        _appSplit = new SplitContainer { Dock = DockStyle.Fill, Orientation = Orientation.Horizontal, BackColor = Theme.Border, SplitterWidth = D(6) };
        _appSplit.Panel1.Controls.Add(_appList);
        var detailHost = new Panel { Dock = DockStyle.Fill, Padding = new Padding(D(16), D(12), D(16), D(12)), BackColor = Color.White };
        detailHost.Controls.Add(_appDetail);
        _appSplit.Panel2.Controls.Add(detailHost);

        _appsTab.Controls.Add(_appSplit);
        _appsTab.Controls.Add(legend);
        _appsTab.Resize += (_, _) =>
        {
            if (_appSplit.Height > D(300) && !_appSplitInitialized)
            {
                _appSplit.SplitterDistance = (int)(_appSplit.Height * 0.5);
                _appSplitInitialized = true;
            }
        };
    }

    private bool _appSplitInitialized;

    private static string Hint(AppGroup g)
    {
        if (g.LeakSuspect) return "⚠ wächst stetig, möglicher Leak";
        if (g.Notes.Any(n => n.Contains("kein Limit"))) return "⚠ SQL Server ohne Speicherlimit";
        if (g.HiddenBytes > 0) return g.HiddenLabel ?? "";
        return g.Kind switch
        {
            AppKind.CacheGreedy => "reserviert RAM absichtlich, begrenzbar",
            AppKind.VirtualMachine => "VM: RAM gehört dem Gast",
            _ when g.Known is null && g.TotalRam >= AppGrouper.LargeUnknownThreshold => "großer unbekannter Verbraucher",
            _ => "",
        };
    }

    private static string[] Cells(AppGroup g) => new[]
    {
        g.Name,
        g.Known?.KindLabel ?? "",
        Fmt.Bytes(g.TotalRam),
        g.HiddenBytes > 0 ? Fmt.Bytes(g.HiddenBytes) : "",
        g.Processes.Count.ToString(Fmt.De),
        Fmt.Bytes(g.PrivateBytes),
        Fmt.Bytes(g.NotResident),
        g.HardFaultsPerSec >= 0.5 ? Fmt.Num(g.HardFaultsPerSec) : "",
        Math.Abs(g.GrowthMbPerMin) >= 1 ? Fmt.Num(g.GrowthMbPerMin, "+0;−0") : "",
        Hint(g),
    };

    private void UpdateApps()
    {
        _appList.BeginUpdate();
        try
        {
            var seen = new HashSet<string>();
            foreach (var g in _groups)
            {
                seen.Add(g.Key);
                var cells = Cells(g);
                if (!_appItems.TryGetValue(g.Key, out var item))
                {
                    item = new ListViewItem(cells) { Name = g.Key, UseItemStyleForSubItems = true };
                    _appItems[g.Key] = item;
                    _appList.Items.Add(item);
                }
                else
                {
                    for (int i = 0; i < cells.Length; i++)
                        if (item.SubItems[i].Text != cells[i]) item.SubItems[i].Text = cells[i];
                }
                item.Tag = g;
                var color = Theme.KindRowColor(g.Kind, g.LeakSuspect);
                if (item.BackColor != color) item.BackColor = color;
            }
            foreach (var key in _appItems.Keys.Where(k => !seen.Contains(k)).ToList())
            {
                _appList.Items.Remove(_appItems[key]);
                _appItems.Remove(key);
            }
            SortApps();
        }
        finally
        {
            _appList.EndUpdate();
        }
        UpdateAppDetail(force: false);
    }

    private void SortApps()
    {
        _appList.ListViewItemSorter = new AppComparer(_sortColumn, _sortDescending);
        _appList.Sort();
    }

    private void SelectApp(string key)
    {
        UpdateApps();
        if (_appItems.TryGetValue(key, out var item))
        {
            _appList.SelectedItems.Clear();
            item.Selected = true;
            item.Focused = true;
            item.EnsureVisible();
            _appList.Focus();
        }
    }

    private sealed class AppComparer : IComparer
    {
        private readonly int _col;
        private readonly bool _desc;

        public AppComparer(int col, bool desc)
        {
            _col = col;
            _desc = desc;
        }

        public int Compare(object? x, object? y)
        {
            if ((x as ListViewItem)?.Tag is not AppGroup a || (y as ListViewItem)?.Tag is not AppGroup b) return 0;
            int c = _col switch
            {
                0 => string.Compare(a.Name, b.Name, StringComparison.CurrentCultureIgnoreCase),
                1 => string.Compare(a.Known?.KindLabel, b.Known?.KindLabel, StringComparison.CurrentCultureIgnoreCase),
                2 => a.TotalRam.CompareTo(b.TotalRam),
                3 => a.HiddenBytes.CompareTo(b.HiddenBytes),
                4 => a.Processes.Count.CompareTo(b.Processes.Count),
                5 => a.PrivateBytes.CompareTo(b.PrivateBytes),
                6 => a.NotResident.CompareTo(b.NotResident),
                7 => a.HardFaultsPerSec.CompareTo(b.HardFaultsPerSec),
                8 => a.GrowthMbPerMin.CompareTo(b.GrowthMbPerMin),
                _ => string.Compare(Hint(a), Hint(b), StringComparison.CurrentCultureIgnoreCase),
            };
            if (c == 0) c = a.TotalRam.CompareTo(b.TotalRam);
            return _desc ? -c : c;
        }
    }

    private void UpdateAppDetail(bool force)
    {
        var item = _appList.SelectedItems.Count > 0 ? _appList.SelectedItems[0] : null;
        if (item?.Tag is not AppGroup g)
        {
            if (_detailKey is not null || _appDetail.TextLength == 0)
            {
                _detailKey = null;
                _detailText = null;
                _appDetail.Clear();
                Append("Wähle oben eine App aus.", Theme.Base, Theme.Muted);
                Append("\n\nDann siehst du hier, warum sie so viel RAM braucht, was normal ist und wie du sie begrenzen kannst.", Theme.Base, Theme.Muted);
            }
            return;
        }

        bool sameApp = g.Key == _detailKey;
        // Nicht neu aufbauen, während der Nutzer Text markiert/kopiert, und höchstens alle 5 s.
        if (sameApp && !force && (_appDetail.Focused || DateTime.Now - _detailBuilt < TimeSpan.FromSeconds(5))) return;
        var parts = DetailParts(g);
        string plain = string.Concat(parts.Select(p => p.Text));
        if (sameApp && !force && plain == _detailText) return;

        var scroll = new Point();
        if (sameApp) NativeMethods.SendMessage(_appDetail.Handle, NativeMethods.EmGetScrollPos, IntPtr.Zero, ref scroll);
        _detailKey = g.Key;
        _detailText = plain;
        _detailBuilt = DateTime.Now;
        _appDetail.SuspendLayout();
        _appDetail.Clear();
        foreach (var (text, font, color) in parts) Append(text, font, color);
        _appDetail.SelectionStart = 0;
        _appDetail.SelectionLength = 0;
        if (sameApp) NativeMethods.SendMessage(_appDetail.Handle, NativeMethods.EmSetScrollPos, IntPtr.Zero, ref scroll);
        else _appDetail.ScrollToCaret();
        _appDetail.ResumeLayout();
    }

    private DateTime _detailBuilt = DateTime.MinValue;

    private List<(string Text, Font Font, Color Color)> DetailParts(AppGroup g)
    {
        var p = new List<(string, Font, Color)>();
        void H(string t) => p.Add(("\n" + t + "\n", Theme.Bold, Theme.Accent));
        void T(string t) => p.Add((t + "\n", Theme.Base, Theme.Text));

        p.Add((g.Name + "\n", Theme.Headline, Theme.Text));
        var line = $"RAM: {Fmt.Bytes(g.TotalRam)}";
        if (g.HiddenBytes > 0) line += $"  (davon {Fmt.Bytes(g.HiddenBytes)} {g.HiddenLabel})";
        line += $"  ·  Zugesagt: {Fmt.Bytes(g.PrivateBytes)}  ·  Nicht im RAM: {Fmt.Bytes(g.NotResident)}  ·  {g.Processes.Count} Prozess(e)";
        if (g.Known is { KindLabel.Length: > 0 } k) line += $"  ·  {k.KindLabel}";
        p.Add((line + "\n", Theme.Base, Theme.Muted));

        if (g.Notes.Count > 0)
        {
            p.Add(("\nBeobachtungen\n", Theme.Bold, Theme.LevelColor(Level.Warnung)));
            foreach (var n in g.Notes) T("• " + n);
        }

        if (g.Known is { } known)
        {
            H("Warum so viel?");
            T(known.Explanation);
            H("Was ist normal?");
            T(known.Normal);
            H("So grenzt du es ein");
            int i = 1;
            foreach (var tip in known.Tips)
            {
                var lines = tip.Split('\n');
                T($"{i++}. {lines[0]}");
                foreach (var code in lines.Skip(1)) p.Add(("     " + code + "\n", Theme.Mono, Color.FromArgb(17, 94, 89)));
            }
        }
        else
        {
            var first = g.Processes.FirstOrDefault()?.Sample;
            H("Was ist das?");
            T(first?.Description is { } d ? $"{d}. RamWächter kennt dieses Programm nicht genauer." : "RamWächter kennt dieses Programm nicht genauer.");
            if (first?.Path is { } path) T("Pfad: " + path);
            H("So grenzt du es ein");
            T("1. Spalte „Trend“ beobachten: Wächst der Wert über viele Minuten stetig, ist es vermutlich ein Speicherleck. Dann hilft ein Neustart oder Update des Programms.");
            T("2. In den Einstellungen des Programms nach Cache- oder Speichergrenzen suchen.");
            T("3. Wird es nicht gebraucht: beenden und den Autostart abschalten (Task-Manager → Autostart-Apps).");
        }

        H("Prozesse");
        p.Add(($"{"PID",7}  {"privat",9}  {"zugesagt",9}  {"nicht im RAM",12}  {"HF/s",6}  Name / gestartet von\n", Theme.Mono, Theme.Muted));
        foreach (var tp in g.Processes.OrderByDescending(x => x.Sample.PrivateWorkingSet).Take(40))
        {
            var s = tp.Sample;
            string parent = tp.ParentName is null ? "" : $"  ← {tp.ParentName}";
            p.Add(($"{s.Pid,7}  {Fmt.Bytes(s.PrivateWorkingSet),9}  {Fmt.Bytes(s.PrivateBytes),9}  {Fmt.Bytes(s.NotResident),12}  {Fmt.Num(tp.HardFaultsPerSec),6}  {s.Name}{parent}\n",
                   Theme.Mono, Theme.Text));
        }
        if (g.Processes.Count > 40) T($"… und {g.Processes.Count - 40} weitere");
        return p;
    }

    private void Append(string text, Font font, Color color)
    {
        _appDetail.SelectionStart = _appDetail.TextLength;
        _appDetail.SelectionLength = 0;
        _appDetail.SelectionFont = font;
        _appDetail.SelectionColor = color;
        _appDetail.AppendText(text);
    }
}
