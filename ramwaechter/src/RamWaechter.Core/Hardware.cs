using System.Text.RegularExpressions;

namespace RamWaechter.Core;

public sealed record MemoryModule
{
    public string Slot { get; init; } = "";
    public string Bank { get; init; } = "";
    public ulong Capacity { get; init; }
    /// <summary>SMBIOS-Speichertyp (26 = DDR4, 34 = DDR5 …).</summary>
    public int SmbiosType { get; init; }
    /// <summary>SMBIOS-Formfaktor (8 = DIMM, 12 = SO-DIMM).</summary>
    public int FormFactor { get; init; }
    /// <summary>Maximaler Takt laut Modul in MT/s.</summary>
    public uint Speed { get; init; }
    /// <summary>Tatsächlich eingestellter Takt in MT/s.</summary>
    public uint ConfiguredSpeed { get; init; }
    public string Manufacturer { get; init; } = "";
    public string PartNumber { get; init; } = "";
    public string Serial { get; init; } = "";
    public uint ConfiguredVoltageMv { get; init; }

    public string TypeName => Hardware.TypeName(SmbiosType);
    public string FormFactorName => FormFactor switch { 8 => "DIMM", 12 => "SO-DIMM", 0 or 1 or 2 => "unbekannt", _ => "verlötet/sonstige" };
    public bool IsSoldered => Hardware.IsLowPower(SmbiosType);
}

public sealed record MemoryHardware
{
    public IReadOnlyList<MemoryModule> Modules { get; init; } = Array.Empty<MemoryModule>();
    /// <summary>Steckplätze laut BIOS (Win32_PhysicalMemoryArray.MemoryDevices); bei manchen Boards falsch.</summary>
    public int SlotCount { get; init; }
    public ulong MaxCapacity { get; init; }
    public string SystemName { get; init; } = "";
    public string Board { get; init; } = "";
    public bool IsLaptop { get; init; }

    public int FreeSlots => Math.Max(0, SlotCount - Modules.Count);
    public ulong InstalledCapacity => (ulong)Modules.Sum(m => (decimal)m.Capacity);
    public int SmbiosType => Modules.GroupBy(m => m.SmbiosType).OrderByDescending(g => g.Count()).Select(g => g.Key).FirstOrDefault();
    public string TypeName => Hardware.TypeName(SmbiosType);
    public bool AllSoldered => Modules.Count > 0 && Modules.All(m => m.IsSoldered);
}

public sealed record ChannelReport(string Mode, IReadOnlyList<string> Notes, bool SingleChannel);

public static class Hardware
{
    public static string TypeName(int smbios) => smbios switch
    {
        20 => "DDR",
        21 => "DDR2",
        24 => "DDR3",
        26 => "DDR4",
        27 => "LPDDR",
        28 => "LPDDR2",
        29 => "LPDDR3",
        30 => "LPDDR4",
        34 => "DDR5",
        35 => "LPDDR5",
        0 or 1 or 2 => "unbekannt",
        _ => $"Typ {smbios}",
    };

    public static bool IsLowPower(int smbios) => smbios is 27 or 28 or 29 or 30 or 35;

    private static readonly Regex[] ChannelPatterns =
    {
        new(@"Controller\s*(\d)\D*Channel\s*([A-H])", RegexOptions.IgnoreCase),
        new(@"Channel\s*([A-H])", RegexOptions.IgnoreCase),
        new(@"DIMM[_\s-]?([A-H])\d", RegexOptions.IgnoreCase),
        new(@"\bCH(?:ANNEL)?[_\s-]?([A-H])\b", RegexOptions.IgnoreCase),
        new(@"^([A-H])\d$", RegexOptions.IgnoreCase),
    };

    /// <summary>Kanal-Kennung aus Steckplatz- und Bank-Bezeichnung (z. B. „DIMM_A1“, „Controller0-ChannelA-DIMM0“, „P0 CHANNEL B“).</summary>
    public static string? ChannelOf(MemoryModule m)
    {
        foreach (var text in new[] { m.Slot, m.Bank, m.Bank + " " + m.Slot })
        {
            foreach (var re in ChannelPatterns)
            {
                var match = re.Match(text.Trim());
                if (!match.Success) continue;
                return match.Groups.Count > 2
                    ? $"{match.Groups[1].Value}{match.Groups[2].Value.ToUpperInvariant()}"
                    : match.Groups[1].Value.ToUpperInvariant();
            }
        }
        return null;
    }

    public static ChannelReport AnalyzeChannels(MemoryHardware hw)
    {
        var mods = hw.Modules;
        var notes = new List<string>();
        if (mods.Count == 0) return new ChannelReport("unbekannt", notes, false);

        if (mods.Count == 1 && !mods[0].IsSoldered)
        {
            notes.Add("Nur ein Riegel steckt: Der Speicher läuft im Single-Channel-Modus mit halber Bandbreite. " +
                      "Ein zweiter, gleicher Riegel bringt vor allem bei integrierter Grafik, Spielen und Builds spürbar mehr Leistung, unabhängig von der Größe.");
            return new ChannelReport("Single-Channel", notes, true);
        }

        var channels = mods.Select(ChannelOf).ToList();
        string mode;
        bool single = false;
        if (channels.All(c => c is not null))
        {
            int distinct = channels.Distinct().Count();
            if (distinct >= 2) mode = distinct == 2 ? "Dual-Channel" : $"{distinct} Kanäle";
            else
            {
                mode = "Single-Channel (alle Riegel in einem Kanal)";
                single = true;
                notes.Add("Alle Riegel stecken laut BIOS im selben Kanal. Im Mainboard-Handbuch nachsehen, welche Slots für Dual-Channel gedacht sind (meist A2 + B2) und umstecken.");
            }
        }
        else
        {
            mode = mods.Count >= 2 ? "vermutlich Dual-Channel" : "unbekannt";
        }

        if (mods.Select(m => m.Capacity).Distinct().Count() > 1 && !single)
        {
            mode += " (Flex-Modus)";
            notes.Add("Unterschiedlich große Riegel: Nur der gleich große Teil läuft im Dual-Channel, der Rest mit halber Bandbreite (Intel Flex Memory). Für volle Leistung gleich große Riegel verwenden.");
        }
        if (mods.Select(m => m.PartNumber.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).Count() > 1)
            notes.Add("Gemischte Riegel (verschiedene Teilenummern). Läuft meist, aber der langsamste Riegel bestimmt Takt und Timings. Bei Instabilität einzeln testen.");

        var slow = mods.Where(m => m.Speed > 0 && m.ConfiguredSpeed > 0 && m.ConfiguredSpeed < m.Speed).ToList();
        if (slow.Count > 0)
            notes.Add($"Der RAM läuft mit {slow[0].ConfiguredSpeed} MT/s, obwohl die Riegel {slow[0].Speed} MT/s melden. " +
                      (mods.Count >= 4 ? "Mit 4 Riegeln senkt das Mainboard den Takt oft absichtlich, besonders bei DDR5. " : "") +
                      "Im BIOS prüfen, ob XMP/EXPO aktiviert ist.");
        else if (mods.Any(m => m.ConfiguredSpeed > 0))
            notes.Add($"Takt: {mods.Max(m => m.ConfiguredSpeed)} MT/s. Ist der Riegel laut Teilenummer für mehr ausgelegt, im BIOS XMP/EXPO aktivieren.");

        return new ChannelReport(mode, notes, single);
    }
}
