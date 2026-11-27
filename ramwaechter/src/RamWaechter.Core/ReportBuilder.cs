using System.Text;

namespace RamWaechter.Core;

/// <summary>Textbericht zum Kopieren/Speichern (z. B. für ein Forum oder den Kollegen von der IT).</summary>
public static class ReportBuilder
{
    public static string Build(
        SystemSample s,
        Verdict ram,
        Verdict paging,
        IReadOnlyList<BreakdownItem> breakdown,
        IReadOnlyList<AppGroup> groups,
        MemoryHardware? hw,
        UpgradeAdvice? upgrade,
        PagefileInfo pf)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"RamWächter-Bericht vom {s.Time.ToString("dd.MM.yyyy HH:mm", Fmt.De)}");
        sb.AppendLine(new string('=', 50));
        sb.AppendLine();

        sb.AppendLine("Bremst der RAM?  " + ram.Headline);
        foreach (var r in ram.Reasons) sb.AppendLine("  - " + r);
        sb.AppendLine();

        sb.AppendLine("Aufteilung des RAM");
        foreach (var b in breakdown.Where(b => b.Bytes > 0))
            sb.AppendLine($"  {b.Label,-24} {Fmt.Bytes(b.Bytes),10}");
        sb.AppendLine();

        sb.AppendLine("Größte Verbraucher");
        foreach (var g in groups.Take(15))
        {
            string kind = g.Known?.KindLabel is { Length: > 0 } k ? $"  [{k}]" : "";
            string hidden = g.HiddenBytes > 0 ? $" (davon {Fmt.Bytes(g.HiddenBytes)} {g.HiddenLabel})" : "";
            sb.AppendLine($"  {Fmt.Bytes(g.TotalRam),10}  {g.Name}{hidden}{kind}");
            foreach (var n in g.Notes) sb.AppendLine("              · " + n);
        }
        sb.AppendLine();

        sb.AppendLine("Auslagerung: " + paging.Headline);
        foreach (var r in paging.Reasons) sb.AppendLine("  - " + r);
        foreach (var f in pf.Files)
            sb.AppendLine($"  {f.Path}: {Fmt.Bytes(f.CurrentUsageBytes)} belegt von {Fmt.Bytes(f.AllocatedBytes)}, Spitze {Fmt.Bytes(f.PeakUsageBytes)}");
        sb.AppendLine();

        if (hw is not null)
        {
            sb.AppendLine($"Hardware: {hw.SystemName} / {hw.Board}");
            sb.AppendLine($"  Steckplätze: {hw.Modules.Count} belegt von {hw.SlotCount}, max. {Fmt.Bytes(hw.MaxCapacity)}");
            foreach (var m in hw.Modules)
                sb.AppendLine($"  {m.Slot} ({m.Bank}): {Fmt.Bytes(m.Capacity)} {m.TypeName} {m.FormFactorName}, {m.ConfiguredSpeed}/{m.Speed} MT/s, {m.Manufacturer.Trim()} {m.PartNumber.Trim()}");
            var ch = Hardware.AnalyzeChannels(hw);
            sb.AppendLine($"  Kanalmodus: {ch.Mode}");
            foreach (var n in ch.Notes) sb.AppendLine("  - " + n);
            sb.AppendLine();
        }

        if (upgrade is not null)
        {
            sb.AppendLine("Upgrade: " + upgrade.Headline);
            foreach (var r in upgrade.Reasons) sb.AppendLine("  - " + r);
            foreach (var o in upgrade.Options) sb.AppendLine("  > " + o);
            foreach (var c in upgrade.Caveats) sb.AppendLine("  ! " + c);
        }
        return sb.ToString();
    }
}
