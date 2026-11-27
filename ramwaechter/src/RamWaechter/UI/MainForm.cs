using System.Diagnostics;
using System.Drawing;
using System.Security.Principal;
using System.Windows.Forms;
using RamWaechter.Collect;
using RamWaechter.Core;

namespace RamWaechter.UI;

internal sealed partial class MainForm : Form
{
    private static readonly TimeSpan PagefileInterval = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan SaveInterval = TimeSpan.FromMinutes(1);

    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 2000 };
    private SystemCollector? _collector;
    private readonly ProcessTracker _tracker = new();
    private readonly History _history = new();
    private readonly PeakStore _peaks;
    private PagefileInfo _pagefile = PagefileInfo.Empty;
    private DateTime _lastPagefile = DateTime.MinValue;
    private DateTime _lastSave = DateTime.Now;
    private MemoryHardware? _hardware;
    private bool _busy;
    private readonly bool _isAdmin;

    private SystemSample? _sys;
    private List<AppGroup> _groups = new();
    private IReadOnlyList<BreakdownItem> _breakdown = Array.Empty<BreakdownItem>();
    private Verdict? _ramVerdict, _pagingVerdict;
    private UpgradeAdvice? _upgrade;

    private readonly TabControl _tabs = new() { Dock = DockStyle.Fill };
    private readonly TabPage _overviewTab = new("Übersicht");
    private readonly TabPage _appsTab = new("Apps");
    private readonly TabPage _pagingTab = new("Auslagerung");
    private readonly TabPage _hardwareTab = new("Hardware & Upgrade");
    private readonly Label _status = new() { AutoSize = true, ForeColor = Theme.Muted, Font = Theme.Base };

    public MainForm()
    {
        _isAdmin = IsAdministrator();
        _peaks = new PeakStore(Path.Combine(Program.DataDir, "tageswerte.json"));

        Text = "RamWächter – Arbeitsspeicher verstehen";
        AutoScaleMode = AutoScaleMode.None;
        Font = Theme.Base;
        BackColor = Theme.Page;
        Size = new Size(D(1320), D(900));
        MinimumSize = new Size(D(900), D(600));
        StartPosition = FormStartPosition.CenterScreen;

        BuildOverview();
        BuildApps();
        BuildPaging();
        BuildHardware();
        _tabs.TabPages.AddRange(new[] { _overviewTab, _appsTab, _pagingTab, _hardwareTab });
        _tabs.Font = Theme.Bold;
        _tabs.Padding = new Point(D(14), D(6));
        _tabs.SelectedIndexChanged += (_, _) => RefreshVisibleTab();

        Controls.Add(_tabs);
        Controls.Add(BuildHeader());

        _timer.Tick += OnTick;
        Load += OnLoad;
        FormClosing += (_, _) =>
        {
            _timer.Stop();
            _peaks.Save();
            _collector?.Dispose();
        };
    }

    private int D(int px) => (int)Math.Round(px * DeviceDpi / 96.0);

    private Control BuildHeader()
    {
        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            BackColor = Theme.Card,
            Padding = new Padding(D(16), D(10), D(16), D(10)),
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var left = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Margin = Padding.Empty };
        left.Controls.Add(new Label { Text = "RamWächter", Font = Theme.Title, ForeColor = Theme.Text, AutoSize = true, Margin = Padding.Empty });
        _status.Text = "Messung startet …";
        left.Controls.Add(_status);

        var right = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, WrapContents = false, Anchor = AnchorStyles.Right, Margin = Padding.Empty };
        right.Controls.Add(MakeButton("Bericht kopieren", (_, _) => CopyReport()));
        right.Controls.Add(MakeButton("Bericht speichern …", (_, _) => SaveReport()));
        if (!_isAdmin)
            right.Controls.Add(MakeButton("Als Administrator neu starten", (_, _) => RestartAsAdmin()));

        header.Controls.Add(left, 0, 0);
        header.Controls.Add(right, 1, 0);
        return header;
    }

    private Button MakeButton(string text, EventHandler click)
    {
        var b = new Button
        {
            Text = text,
            AutoSize = true,
            FlatStyle = FlatStyle.System,
            Padding = new Padding(D(8), D(4), D(8), D(4)),
            Margin = new Padding(D(6), D(8), 0, D(8)),
        };
        b.Click += click;
        return b;
    }

    private async void OnLoad(object? sender, EventArgs e)
    {
        try
        {
            _collector = await Task.Run(() => new SystemCollector());
            _pagefile = await Task.Run(WmiCollector.ReadPagefile);
            _lastPagefile = DateTime.Now;
        }
        catch (Exception ex)
        {
            _status.Text = "Fehler beim Start der Messung: " + ex.Message;
            return;
        }
        _timer.Start();
        OnTick(null, EventArgs.Empty);

        try
        {
            _hardware = await Task.Run(WmiCollector.ReadHardware);
        }
        catch (Exception)
        {
            _hardware = new MemoryHardware();
        }
        RefreshVisibleTab();
    }

    private async void OnTick(object? sender, EventArgs e)
    {
        if (_busy || _collector is null) return;
        _busy = true;
        try
        {
            var collector = _collector;
            var pagefile = _pagefile;
            bool refreshPagefile = DateTime.Now - _lastPagefile > PagefileInterval;
            var (snap, groups, pf) = await Task.Run(() =>
            {
                if (refreshPagefile) pagefile = WmiCollector.ReadPagefile();
                var s = collector.Collect(pagefile);
                var tracked = _tracker.Update(s.Processes, s.System.Time);
                return (s, AppGrouper.Build(tracked, s.Vms, s.Sql, s.System.Total), pagefile);
            });
            if (refreshPagefile) _lastPagefile = DateTime.Now;
            _pagefile = pf;

            _sys = snap.System;
            _groups = groups;
            _history.Add(_sys);
            _peaks.Add(_sys);
            _breakdown = Breakdown.Compute(_sys, _groups);
            _ramVerdict = RamAnalyzer.Evaluate(_sys, _history, _groups);
            _pagingVerdict = PagingAnalyzer.Evaluate(_sys, _history, _pagefile, _groups);
            if (_hardware is not null)
            {
                ulong installed = _sys.Installed;
                var profile = new UsageProfile(
                    installed,
                    Math.Max(_history.PeakDemand?.Demand ?? 0, _peaks.PeakDemand(installed)),
                    _peaks.PressureFraction(installed),
                    _peaks.Measured(installed),
                    Breakdown.Limitable(_groups));
                _upgrade = UpgradeAdvisor.Advise(_hardware, profile);
            }

            if (DateTime.Now - _lastSave > SaveInterval)
            {
                _lastSave = DateTime.Now;
                _peaks.Save();
            }

            _status.Text = StatusText(snap);
            RefreshVisibleTab();
        }
        catch (Exception ex)
        {
            _status.Text = "Messfehler: " + ex.Message;
        }
        finally
        {
            _busy = false;
        }
        CheckSelfTest();
    }

    private string StatusText(Snapshot snap)
    {
        string since = _history.Start is DateTime t ? Fmt.Duration(DateTime.Now - t) : "–";
        string admin = _isAdmin ? "Administrator" : "ohne Adminrechte (Pfade von Systemprozessen und evtl. Hyper-V-Werte fehlen)";
        return $"Misst seit {since} alle 2 s · {snap.Processes.Count} Prozesse · {admin}";
    }

    private void RefreshVisibleTab()
    {
        if (_sys is null) return;
        var tab = _tabs.SelectedTab;
        if (tab == _overviewTab) UpdateOverview();
        else if (tab == _appsTab) UpdateApps();
        else if (tab == _pagingTab) UpdatePaging();
        else if (tab == _hardwareTab) UpdateHardware();
    }

    private void ShowGroup(AppGroup g)
    {
        _tabs.SelectedTab = _appsTab;
        SelectApp(g.Key);
    }

    private string? BuildReport() =>
        _sys is null || _ramVerdict is null || _pagingVerdict is null
            ? null
            : ReportBuilder.Build(_sys, _ramVerdict, _pagingVerdict, _breakdown, _groups, _hardware, _upgrade, _pagefile);

    private void CopyReport()
    {
        var text = BuildReport();
        if (text is null) return;
        Clipboard.SetText(text);
        _status.Text = "Bericht in die Zwischenablage kopiert.";
    }

    private void SaveReport()
    {
        var text = BuildReport();
        if (text is null) return;
        using var dlg = new SaveFileDialog
        {
            Filter = "Textdatei (*.txt)|*.txt",
            FileName = $"RamWaechter-Bericht-{DateTime.Now:yyyy-MM-dd-HHmm}.txt",
        };
        if (dlg.ShowDialog(this) == DialogResult.OK)
            File.WriteAllText(dlg.FileName, text, System.Text.Encoding.UTF8);
    }

    private void RestartAsAdmin()
    {
        try
        {
            _peaks.Save();
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = true, Verb = "runas" });
            Close();
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // Benutzer hat die UAC-Abfrage abgebrochen.
        }
    }

    private static bool IsAdministrator()
    {
        using var id = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
    }
}
