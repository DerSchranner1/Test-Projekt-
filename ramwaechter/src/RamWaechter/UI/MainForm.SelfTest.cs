using System.Drawing;
using System.Drawing.Imaging;
using System.Text;
using System.Windows.Forms;
using RamWaechter.Core;

namespace RamWaechter.UI;

/// <summary>Selbsttest für den Build: misst einige Runden und speichert Bericht und Bilder aller Tabs.</summary>
internal sealed partial class MainForm
{
    private const int SelfTestSamples = 8;
    private bool _selfTestDone;
    private readonly DateTime _started = DateTime.Now;

    public string? SelfTestDir { get; init; }
    public int ExitCode { get; private set; }

    private void CheckSelfTest()
    {
        if (SelfTestDir is null || _selfTestDone) return;
        bool timeout = DateTime.Now - _started > TimeSpan.FromSeconds(90);
        if (!timeout && (_history.Samples.Count < SelfTestSamples || _hardware is null)) return;
        _selfTestDone = true;
        BeginInvoke(RunSelfTest);
    }

    private void RunSelfTest()
    {
        string dir = SelfTestDir!;
        var problems = new List<string>();
        try
        {
            if (_sys is null) problems.Add("Keine Messung erhalten.");
            else
            {
                if (_sys.Total == 0) problems.Add("Gesamt-RAM ist 0 (GetPerformanceInfo).");
                if (_sys.Standby == 0 && _sys.FreeZero == 0) problems.Add("Standby/Frei sind 0 (PDH-Zähler).");
                if (_groups.Sum(g => g.Processes.Count) < 30) problems.Add("Zu wenige Prozesse gefunden (NtQuerySystemInformation).");
                if (_groups.All(g => g.PrivateWs == 0)) problems.Add("Privater Arbeitssatz überall 0.");
            }
            if (_hardware is null || _hardware.Modules.Count == 0) problems.Add("Hinweis: keine RAM-Riegel per WMI (auf VMs normal).");

            var shots = new (TabPage Tab, string Name)[]
            {
                (_overviewTab, "1-uebersicht"), (_appsTab, "2-apps"), (_pagingTab, "3-auslagerung"), (_hardwareTab, "4-hardware"),
            };
            foreach (var (tab, name) in shots)
            {
                _tabs.SelectedTab = tab;
                RefreshVisibleTab();
                if (tab == _appsTab)
                {
                    var pick = _groups.FirstOrDefault(g => g.Known is { Kind: not AppKind.System }) ?? _groups.FirstOrDefault();
                    if (pick is not null) SelectApp(pick.Key);
                    File.WriteAllText(Path.Combine(dir, "2-apps-details.txt"), _appDetail.Text, Encoding.UTF8);
                }
                Application.DoEvents();
                using var bmp = Render(tab);
                bmp.Save(Path.Combine(dir, name + ".png"), ImageFormat.Png);
            }

            File.WriteAllText(Path.Combine(dir, "bericht.txt"), BuildReport() ?? "(kein Bericht)", Encoding.UTF8);
            File.WriteAllText(Path.Combine(dir, "diagnose.txt"), Diagnose(problems), Encoding.UTF8);
            ExitCode = problems.Any(p => !p.StartsWith("Hinweis")) ? 1 : 0;
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(dir, "fehler.log"), ex.ToString());
            ExitCode = 2;
        }
        Close();
    }

    private string Diagnose(List<string> problems)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"Selbsttest {DateTime.Now:yyyy-MM-dd HH:mm:ss}, DPI {DeviceDpi}, Admin: {_isAdmin}");
        sb.AppendLine($"Messungen: {_history.Samples.Count}, Prozesse: {_groups.Sum(g => g.Processes.Count)}, App-Gruppen: {_groups.Count}");
        sb.AppendLine(_collector?.Diagnose() ?? "kein Collector");
        if (_hardware is { } hw)
            sb.AppendLine($"WMI: {hw.Modules.Count} Riegel, {hw.SlotCount} Slots, max {Fmt.Bytes(hw.MaxCapacity)}, System „{hw.SystemName}“, Board „{hw.Board}“, Laptop {hw.IsLaptop}");
        sb.AppendLine($"Auslagerungsdatei: {_pagefile.Files.Count} Datei(en), {Fmt.Bytes(_pagefile.Used)} von {Fmt.Bytes(_pagefile.Allocated)}, automatisch {_pagefile.AutomaticManaged}");
        if (_sys is { } s)
            sb.AppendLine($"System: Total {Fmt.Bytes(s.Total)}, verfügbar {Fmt.Bytes(s.Available)}, Standby {Fmt.Bytes(s.Standby)}, Frei {Fmt.Bytes(s.FreeZero)}, " +
                          $"Geändert {Fmt.Bytes(s.Modified)}, Commit {Fmt.Bytes(s.Committed)}/{Fmt.Bytes(s.CommitLimit)}, komprimiert {Fmt.Bytes(s.Compressed)}, " +
                          $"Cache {Fmt.Bytes(s.SystemCacheResident)}, NP-Pool {Fmt.Bytes(s.NonPagedPool)}, Lesezeit {s.DiskSecPerRead}");
        sb.AppendLine("Top-Gruppen:");
        foreach (var g in _groups.Take(12))
            sb.AppendLine($"  {Fmt.Bytes(g.TotalRam),10}  {g.Processes.Count,3}×  {g.Name}");
        sb.AppendLine(problems.Count == 0 ? "OK" : "Probleme:\n  " + string.Join("\n  ", problems));
        return sb.ToString();
    }

    /// <summary>Zeichnet einen Tab vollständig, auch den nicht sichtbaren, gescrollten Teil.</summary>
    private Bitmap Render(TabPage tab)
    {
        if (tab.Controls.OfType<StackPanel>().FirstOrDefault() is { } sp)
        {
            int width = sp.ClientSize.Width;
            int height = Math.Max(sp.ClientSize.Height, sp.AutoScrollMinSize.Height);
            var bmp = new Bitmap(width, height);
            using (var g = Graphics.FromImage(bmp)) g.Clear(Theme.Page);
            int offset = -sp.AutoScrollPosition.Y;
            foreach (Control c in sp.Controls)
            {
                if (!c.Visible || c.Width <= 0 || c.Height <= 0) continue;
                c.DrawToBitmap(bmp, new Rectangle(c.Left, c.Top + offset, c.Width, c.Height));
            }
            return bmp;
        }
        var shot = new Bitmap(ClientSize.Width, ClientSize.Height);
        DrawToBitmap(shot, new Rectangle(Point.Empty, ClientSize));
        return shot;
    }
}
