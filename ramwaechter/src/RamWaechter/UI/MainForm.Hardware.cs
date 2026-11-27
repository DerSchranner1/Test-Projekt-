using System.Drawing;
using System.Windows.Forms;
using RamWaechter.Core;

namespace RamWaechter.UI;

internal sealed partial class MainForm
{
    private readonly MetricsGrid _hwMetrics = new("Arbeitsspeicher-Hardware");
    private readonly ListCard _sticksCard = new("Steckplätze und Riegel") { ListHeight = 170 };
    private readonly VerdictView _channelView = new("Kanäle und Takt");
    private readonly VerdictView _upgradeView = new("Lohnt sich ein Upgrade?");
    private readonly ListCard _daysCard = new("Verlauf: Tageshöchstwerte", "Grundlage der Upgrade-Empfehlung, gespeichert für 30 Tage") { ListHeight = 200 };
    private MemoryHardware? _shownHardware;
    private DateOnly? _shownDaysStamp;
    private int _shownDaysCount = -1;

    private void BuildHardware()
    {
        foreach (var (t, w, a) in new (string, int, HorizontalAlignment)[]
                 {
                     ("Steckplatz", 190, HorizontalAlignment.Left),
                     ("Bank", 110, HorizontalAlignment.Left),
                     ("Größe", 80, HorizontalAlignment.Right),
                     ("Typ", 70, HorizontalAlignment.Left),
                     ("Bauform", 80, HorizontalAlignment.Left),
                     ("Takt aktiv", 90, HorizontalAlignment.Right),
                     ("Takt max.", 90, HorizontalAlignment.Right),
                     ("Spannung", 80, HorizontalAlignment.Right),
                     ("Hersteller", 120, HorizontalAlignment.Left),
                     ("Teilenummer", 190, HorizontalAlignment.Left),
                     ("Seriennummer", 120, HorizontalAlignment.Left),
                 })
            _sticksCard.List.Columns.Add(t, D(w), a);

        foreach (var (t, w, a) in new (string, int, HorizontalAlignment)[]
                 {
                     ("Datum", 110, HorizontalAlignment.Left),
                     ("RAM", 80, HorizontalAlignment.Right),
                     ("Spitze in Verwendung", 150, HorizontalAlignment.Right),
                     ("Spitze Bedarf", 120, HorizontalAlignment.Right),
                     ("Spitze Zugesichert", 140, HorizontalAlignment.Right),
                     ("Zeit unter Druck", 130, HorizontalAlignment.Right),
                     ("Gemessen", 110, HorizontalAlignment.Right),
                 })
            _daysCard.List.Columns.Add(t, D(w), a);

        var stack = new StackPanel { Dock = DockStyle.Fill };
        stack.Add(_upgradeView);
        stack.Add(_hwMetrics);
        stack.Add(_sticksCard);
        stack.Add(_channelView);
        stack.Add(_daysCard);
        stack.Add(new TextCard("Wie die Empfehlung entsteht",
            "Bedarf = RAM in Verwendung + belegter Platz in der Auslagerungsdatei, also das, was Windows gern im RAM hätte. Maßgeblich ist der höchste Wert über alle gemessenen Tage mit der aktuellen RAM-Größe. Nach einem Upgrade beginnt die Statistik neu.",
            "Empfohlen wird die kleinste sinnvoll kaufbare Größe, die diesen Bedarf plus 30 % Reserve für Dateicache und Wachstum abdeckt. „Dringend“ heißt: Der RAM war voll und Windows musste dabei spürbar auslagern.",
            "Viel „In Verwendung“ allein ist noch kein Grund für ein Upgrade. Programme wie SQL Server, Hyper-V-VMs, WSL oder Java nehmen sich RAM, weil er da ist. Erst begrenzen, dann neu messen. Mehr RAM über den Bedarf hinaus vergrößert nur den Dateicache, und den merkt man kaum."));
        _hardwareTab.Controls.Add(stack);
        _hardwareTab.BackColor = Theme.Page;
    }

    private void UpdateHardware()
    {
        var s = _sys!;
        if (_upgrade is not null)
        {
            var extra = _upgrade.Options.Select(o => "→  " + o).Concat(_upgrade.Caveats.Select(c => "Hinweis: " + c)).ToList();
            _upgradeView.Set(new Verdict(Theme.ToLevel(_upgrade.Need), _upgrade.Headline, _upgrade.Reasons), extra);
        }
        else
        {
            _upgradeView.Set(new Verdict(Level.Hinweis, "Hardware wird gelesen …", Array.Empty<string>()));
        }

        UpdateDays();

        var hw = _hardware;
        if (hw is null || ReferenceEquals(hw, _shownHardware)) return;
        _shownHardware = hw;

        var mods = hw.Modules;
        var channels = Hardware.AnalyzeChannels(hw);
        string speed = mods.Count == 0 ? "–" : $"{mods.Max(m => m.ConfiguredSpeed)} MT/s (max. {mods.Max(m => m.Speed)} MT/s)";
        _hwMetrics.Set(new (string, string, string?)[]
        {
            ("System", string.IsNullOrEmpty(hw.SystemName) ? "–" : hw.SystemName, null),
            ("Mainboard", string.IsNullOrEmpty(hw.Board) ? "–" : hw.Board, "Damit findest du im Handbuch die unterstützten Riegel und die richtige Slot-Belegung."),
            ("Typ", mods.Count == 0 ? "–" : $"{hw.TypeName} {mods[0].FormFactorName}", null),
            ("Eingebaut / nutzbar", $"{Fmt.Bytes(hw.InstalledCapacity)} / {Fmt.Bytes(s.Total)}", "Die Differenz reservieren BIOS bzw. integrierte Grafik."),
            ("Steckplätze", hw.SlotCount == 0 ? "–" : $"{mods.Count} belegt von {hw.SlotCount} · {hw.FreeSlots} frei",
                "Laut BIOS (SMBIOS). Manche Mainboards melden mehr Slots, als verbaut sind, z. B. 4 statt 2. Im Handbuch gegenprüfen."),
            ("Max. unterstützt", hw.MaxCapacity == 0 ? "–" : Fmt.Bytes(hw.MaxCapacity), "Laut BIOS. Bei neueren Boards oft höher, als das BIOS angibt (Herstellerseite)."),
            ("Kanalmodus", channels.Mode, "Dual-Channel verdoppelt die Speicherbandbreite gegenüber einem einzelnen Riegel."),
            ("Takt", speed, "Aktiv eingestellter vs. maximal gemeldeter Takt."),
        });

        var list = _sticksCard.List;
        list.BeginUpdate();
        list.Items.Clear();
        foreach (var m in mods)
        {
            list.Items.Add(new ListViewItem(new[]
            {
                m.Slot, m.Bank, Fmt.Bytes(m.Capacity), m.TypeName, m.FormFactorName,
                m.ConfiguredSpeed > 0 ? $"{m.ConfiguredSpeed} MT/s" : "–",
                m.Speed > 0 ? $"{m.Speed} MT/s" : "–",
                m.ConfiguredVoltageMv > 0 ? $"{m.ConfiguredVoltageMv / 1000.0:0.00} V" : "–",
                m.Manufacturer, m.PartNumber, m.Serial,
            }) { BackColor = m.ConfiguredSpeed > 0 && m.ConfiguredSpeed < m.Speed ? Color.FromArgb(254, 243, 199) : Color.White });
        }
        for (int i = 0; i < hw.FreeSlots; i++)
            list.Items.Add(new ListViewItem(new[] { "(frei)", "", "", "", "", "", "", "", "", "", "" }) { ForeColor = Theme.Muted, BackColor = Color.FromArgb(240, 253, 244) });
        list.EndUpdate();

        Level chLevel = channels.SingleChannel ? Level.Warnung
            : channels.Notes.Any(n => n.Contains("obwohl")) || channels.Mode.Contains("Flex") ? Level.Hinweis
            : Level.Gut;
        string chHead = channels.SingleChannel ? $"{channels.Mode}: Hier wird Leistung verschenkt."
            : chLevel == Level.Hinweis ? $"{channels.Mode}, aber nicht optimal."
            : $"{channels.Mode}. Passt.";
        _channelView.Set(new Verdict(chLevel, chHead, channels.Notes));
    }

    private void UpdateDays()
    {
        var days = _peaks.Days;
        var stamp = DateOnly.FromDateTime(DateTime.Now);
        // Nur jede Minute bzw. bei neuem Tag neu aufbauen.
        if (days.Count == _shownDaysCount && _shownDaysStamp == stamp && DateTime.Now.Second > 4) return;
        _shownDaysCount = days.Count;
        _shownDaysStamp = stamp;

        var list = _daysCard.List;
        list.BeginUpdate();
        list.Items.Clear();
        foreach (var d in days.OrderByDescending(d => d.Date))
        {
            double pressure = d.MeasuredSeconds <= 0 ? 0 : d.PressureSeconds / d.MeasuredSeconds;
            list.Items.Add(new ListViewItem(new[]
            {
                d.Date.ToString("ddd dd.MM.yyyy", Fmt.De),
                Fmt.Bytes(d.Installed),
                Fmt.Bytes(d.PeakInUse),
                Fmt.Bytes(d.PeakDemand),
                Fmt.Bytes(d.PeakCommit),
                Fmt.Pct(pressure),
                Fmt.Duration(TimeSpan.FromSeconds(d.MeasuredSeconds)),
            }) { BackColor = pressure > 0.01 ? Color.FromArgb(254, 226, 226) : Color.White });
        }
        list.EndUpdate();
    }
}
