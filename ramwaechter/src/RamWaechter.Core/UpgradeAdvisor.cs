namespace RamWaechter.Core;

public enum UpgradeNeed
{
    KeinBedarf,
    Optional,
    Empfohlen,
    Dringend,
}

public sealed record UpgradeAdvice(
    UpgradeNeed Need,
    string Headline,
    IReadOnlyList<string> Reasons,
    int TargetGb,
    IReadOnlyList<string> Options,
    IReadOnlyList<string> Caveats);

public sealed record UsageProfile(
    ulong Installed,
    ulong PeakDemand,
    double PressureFraction,
    TimeSpan Measured,
    ulong Limitable);

/// <summary>Leitet aus Messwerten und Hardware ab, ob und wie viel mehr RAM sinnvoll ist.</summary>
public static class UpgradeAdvisor
{
    public static readonly int[] TargetSizesGb = { 8, 16, 24, 32, 48, 64, 96, 128, 192, 256 };
    /// <summary>Reserve für Dateicache und Wachstum über dem gemessenen Spitzenbedarf.</summary>
    public const double Headroom = 1.3;

    public static int[] StickSizesGb(int smbiosType) => smbiosType switch
    {
        24 => new[] { 4, 8 },
        26 => new[] { 8, 16, 32 },
        34 => new[] { 8, 16, 24, 32, 48, 64 },
        _ => new[] { 8, 16, 32 },
    };

    public static int RoundUpTarget(double gb) => TargetSizesGb.FirstOrDefault(s => s >= gb - 0.01, TargetSizesGb[^1]);

    /// <summary>
    /// RAM-Größen, die sich mit diesem System sinnvoll erreichen lassen: 2er- oder 4er-Kits
    /// gleicher Riegel sowie Verdoppeln durch identische Riegel in freien Slots.
    /// </summary>
    public static IReadOnlyList<int> AchievableTotalsGb(MemoryHardware hw)
    {
        if (hw.Modules.Count == 0 || hw.SlotCount <= 0) return TargetSizesGb;
        var sizes = StickSizesGb(hw.SmbiosType);
        var totals = new SortedSet<int>();
        int slots = Math.Max(hw.SlotCount, hw.Modules.Count);
        foreach (var s in sizes)
        {
            if (slots >= 2) totals.Add(2 * s); else totals.Add(s);
            if (slots >= 4) totals.Add(4 * s);
        }
        int installed = (int)Math.Round(hw.InstalledCapacity / Fmt.GiB);
        totals.Add(installed);
        bool identical = hw.Modules.All(m => m.Capacity == hw.Modules[0].Capacity);
        if (identical && hw.FreeSlots >= hw.Modules.Count) totals.Add(installed * 2);
        double max = hw.MaxCapacity / Fmt.GiB;
        return totals.Where(t => max <= 0 || t <= max + 0.5).DefaultIfEmpty(installed).ToList();
    }

    private static int Pick(IReadOnlyList<int> totals, double minGb, int fallback) =>
        totals.Where(t => t >= minGb - 0.01).DefaultIfEmpty(fallback).Min();

    public static UpgradeAdvice Advise(MemoryHardware hw, UsageProfile use)
    {
        double installedGb = use.Installed / Fmt.GiB;
        double demandGb = use.PeakDemand / Fmt.GiB;
        double ratio = installedGb <= 0 ? 0 : demandGb / installedGb;
        var reasons = new List<string>();
        var caveats = new List<string>();
        var options = new List<string>();

        UpgradeNeed need;
        if ((ratio >= 0.95 && use.PressureFraction > 0.02) || use.PressureFraction > 0.10)
            need = UpgradeNeed.Dringend;
        else if (ratio >= 0.85 || use.PressureFraction > 0.005)
            need = UpgradeNeed.Empfohlen;
        else if (ratio >= 0.70)
            need = UpgradeNeed.Optional;
        else
            need = UpgradeNeed.KeinBedarf;

        int installedRounded = RoundUpTarget(installedGb * 0.97);
        var totals = AchievableTotalsGb(hw);
        int maxReachable = totals.Max();
        int wish = Pick(totals, Math.Max(demandGb * Headroom, installedRounded), maxReachable);
        int target = need switch
        {
            UpgradeNeed.KeinBedarf => installedRounded,
            UpgradeNeed.Optional => Math.Max(wish, Pick(totals, installedRounded + 1, installedRounded)),
            _ => Math.Max(wish, Pick(totals, installedRounded + 1, maxReachable)),
        };

        reasons.Add($"Höchster gemessener Bedarf: {Fmt.Gb(demandGb)} (RAM in Verwendung plus Ausgelagertes) bei {Fmt.Gb(installedGb)} eingebaut, also {Fmt.Pct(ratio)}.");
        reasons.Add($"RAM-Mangel mit Ausbremsen: {Fmt.Pct(use.PressureFraction)} der gemessenen Zeit ({Fmt.Duration(use.Measured)} gemessen).");
        if (use.Limitable > 0 && need >= UpgradeNeed.Optional)
            reasons.Add($"{Fmt.Bytes(use.Limitable)} davon reservieren Programme, die sich begrenzen lassen (SQL Server, VMs, WSL, Java …). Probier zuerst die Tipps im Tab „Apps“. Das kostet nichts.");

        string headline = need switch
        {
            UpgradeNeed.Dringend => $"Ja, ein Upgrade lohnt sich deutlich. Ziel: {target} GB.",
            UpgradeNeed.Empfohlen => $"Ja, ein Upgrade ist sinnvoll. Ziel: {target} GB.",
            UpgradeNeed.Optional => $"Kann man machen, muss man nicht. Mit {target} GB hättest du mehr Reserve.",
            _ => "Nein. Mehr RAM würde kaum etwas bringen.",
        };
        if (need >= UpgradeNeed.Optional && target <= installedRounded)
        {
            headline = "Mehr RAM wäre gut, aber dieses System ist laut BIOS schon am Maximum.";
            caveats.Add("Hilfe bringt nur, die Verbraucher zu begrenzen (Tab „Apps“). Die Maximalangabe des BIOS ist manchmal zu niedrig. Beim Hersteller nachsehen, ob größere Riegel freigegeben sind.");
        }
        if (need == UpgradeNeed.KeinBedarf)
            reasons.Add("Mehr RAM würde nur den Dateicache vergrößern. Das merkt man im Alltag praktisch nicht.");
        if (need >= UpgradeNeed.Empfohlen && target > installedRounded)
            reasons.Add($"Mehr als {target} GB bringt bei deinem Nutzungsprofil kaum zusätzlich etwas.");

        if (use.Measured < TimeSpan.FromHours(2))
            caveats.Add($"Erst {Fmt.Duration(use.Measured)} gemessen. Lass RamWächter während eines typischen Arbeitstags laufen (mit SQL Server, IDE, VMs gleichzeitig). Die Höchstwerte werden über Neustarts hinweg gespeichert.");

        var channels = Hardware.AnalyzeChannels(hw);
        BuildOptions(hw, need, target, channels, options, caveats);

        return new UpgradeAdvice(need, headline, reasons, target, options, caveats);
    }

    private static void BuildOptions(MemoryHardware hw, UpgradeNeed need, int target, ChannelReport channels, List<string> options, List<string> caveats)
    {
        var mods = hw.Modules;
        if (mods.Count == 0)
        {
            caveats.Add("Keine Riegel-Informationen verfügbar (WMI). Größe und Typ mit CPU-Z oder im Handbuch prüfen.");
            return;
        }
        if (hw.AllSoldered)
        {
            options.Add($"Der RAM ist {hw.TypeName} und damit fest verlötet. Aufrüsten ist nicht möglich. Hilfe bringt nur, die Verbraucher zu begrenzen (Tab „Apps“).");
            return;
        }

        int type = hw.SmbiosType;
        string typeName = hw.TypeName;
        string form = mods[0].FormFactorName;
        double installedGb = hw.InstalledCapacity / Fmt.GiB;
        double maxGb = hw.MaxCapacity / Fmt.GiB;
        int stickGbEach = (int)Math.Round(mods[0].Capacity / Fmt.GiB);
        bool identical = mods.All(m => m.Capacity == mods[0].Capacity) &&
                         mods.Select(m => m.PartNumber.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).Count() == 1;

        if (channels.SingleChannel && mods.Count == 1)
        {
            options.Add($"Wichtigster Schritt: einen zweiten, gleichen Riegel ({stickGbEach} GB {typeName} {form}, Teilenummer {mods[0].PartNumber.Trim()}) dazustecken. " +
                        $"Das ergibt {stickGbEach * 2} GB und Dual-Channel mit doppelter Bandbreite.");
        }

        if (need == UpgradeNeed.KeinBedarf) return;

        bool secondStickProposed = channels.SingleChannel && mods.Count == 1;
        if (secondStickProposed && stickGbEach * 2 >= target)
        {
            // Der zweite Riegel reicht schon für das Ziel.
        }
        else if (hw.FreeSlots > 0 && identical && hw.FreeSlots >= mods.Count)
        {
            int result = (int)Math.Round(installedGb) * 2;
            if (result >= target || need == UpgradeNeed.Optional)
            {
                options.Add($"Ergänzen: {mods.Count}× {stickGbEach} GB {typeName} {form} dazukaufen (gleiche Teilenummer: {mods[0].PartNumber.Trim()}) → {result} GB. Günstigste Variante, alte Riegel bleiben.");
                if (type == 34 && mods.Count + mods.Count >= 4)
                    caveats.Add("DDR5 mit 4 Riegeln läuft oft deutlich langsamer (z. B. 4800 statt 6000 MT/s) und ist zickiger. Ein 2er-Kit mit größeren Riegeln ist meist die bessere Wahl.");
                caveats.Add("Gleiche Teilenummer ist kein Garant: Einzeln gekaufte Riegel können andere Chips haben. Ein komplettes Kit ist am sichersten.");
            }
        }
        else if (hw.FreeSlots > 0)
        {
            options.Add($"Es sind {hw.FreeSlots} Steckplätze frei, aber die vorhandenen Riegel sind nicht identisch. Ergänzen geht, besser ist aber ein neues Kit (siehe unten).");
        }

        int kitSticks = hw.SlotCount >= 2 ? 2 : 1;
        var sizes = StickSizesGb(type);
        int? kitSize = sizes.Cast<int?>().FirstOrDefault(s => s * kitSticks >= target);
        if (kitSize is int ks && (maxGb <= 0 || ks * kitSticks <= maxGb + 0.5))
        {
            options.Add($"Ersetzen: {kitSticks}er-Kit {kitSticks}× {ks} GB {typeName} {form} → {ks * kitSticks} GB. Volle Dual-Channel-Leistung, alte Riegel lassen sich verkaufen." +
                        (hw.SlotCount >= 4 ? $" Es bleiben {hw.SlotCount - kitSticks} Slots für später frei." : ""));
        }
        else if (hw.SlotCount >= 4)
        {
            int four = sizes.FirstOrDefault(s => s * 4 >= target, sizes[^1]);
            options.Add($"Vollbestückung: 4× {four} GB {typeName} {form} → {four * 4} GB.");
        }

        if (maxGb > 0 && target > maxGb + 0.5)
            caveats.Add($"Laut BIOS unterstützt das System höchstens {Fmt.Gb(maxGb)}. Vor dem Kauf im Handbuch bzw. auf der Herstellerseite prüfen ({hw.Board}).");
        if (hw.SlotCount > 0 && hw.SlotCount < mods.Count)
            caveats.Add("Das BIOS meldet weniger Steckplätze als Riegel. Die Slot-Angaben sind hier unzuverlässig, bitte im Handbuch nachsehen.");
        if (hw.IsLaptop)
            caveats.Add("Notebook: Oft ist ein Teil des RAM verlötet und nur ein Slot frei. Die Slot-Angabe des BIOS vor dem Kauf mit dem Wartungshandbuch des Herstellers abgleichen.");
        else
            caveats.Add($"Slot-Anzahl laut BIOS: {hw.SlotCount}. Manche Boards melden mehr Slots, als verbaut sind. Gegencheck im Handbuch von „{hw.Board}“.");
    }
}
