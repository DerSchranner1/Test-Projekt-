namespace RamWaechter.Core;

/// <summary>Beantwortet „Bremst der RAM gerade aus?“.</summary>
public static class RamAnalyzer
{
    public static readonly TimeSpan RecentWindow = TimeSpan.FromSeconds(60);

    public static Verdict Evaluate(SystemSample s, History history, IReadOnlyList<AppGroup> groups)
    {
        double avail = s.AvailableFraction;
        double input = Nz(history.Average(RecentWindow, x => x.PagesInputPerSec));
        double commit = s.CommitFraction;
        var paging = PagingAnalyzer.EstimateStall(history);

        var reasons = new List<string>();
        Level level;
        string headline;

        if ((avail < 0.05 && input > 300) || commit > 0.95 || (avail < 0.10 && paging.StallFraction > 0.25))
        {
            level = Level.Kritisch;
            headline = "Ja, stark. Der RAM ist voll, und Windows lagert ständig aus.";
        }
        else if ((avail < 0.10 && input > 100) || commit > 0.90 || (avail < 0.15 && paging.StallFraction > 0.10))
        {
            level = Level.Warnung;
            headline = "Ja, spürbar. Der RAM wird knapp.";
        }
        else if (avail < 0.15 || commit > 0.80)
        {
            level = Level.Hinweis;
            headline = "Noch nicht, aber es wird eng.";
        }
        else
        {
            level = Level.Gut;
            headline = "Nein. Der RAM bremst gerade nicht.";
        }

        reasons.Add($"Verfügbar: {Fmt.Bytes(s.Available)} von {Fmt.Bytes(s.Total)} ({Fmt.Pct(avail)}). " +
                    (avail >= 0.15 ? "Genug Reserve." : avail >= 0.10 ? "Wenig Reserve." : "Kaum noch Reserve."));

        if (s.Standby > s.Total / 5 && avail >= 0.10)
            reasons.Add($"Hohe Auslastung ist hier kein Problem: {Fmt.Bytes(s.Standby)} sind Standby-Cache, den Windows sofort freigibt.");

        reasons.Add($"Zugesicherter Speicher (Commit): {Fmt.Bytes(s.Committed)} von maximal {Fmt.Bytes(s.CommitLimit)} ({Fmt.Pct(commit)}). " +
                    (commit > 0.90 ? "Fast am Limit. Programme können abstürzen oder Windows vergrößert die Auslagerungsdatei."
                     : commit > 0.80 ? "Wird knapp."
                     : "Unkritisch."));

        if (input > 50)
        {
            if (avail >= 0.20)
                reasons.Add($"Windows liest {Fmt.Num(input)} Seiten/s von der Platte nach. Bei so viel freiem RAM ist das kein RAM-Mangel, sondern Programme bzw. Dateien werden geladen.");
            else
                reasons.Add($"Windows liest {Fmt.Num(input)} Seiten/s ({Fmt.Num(input * 4096 / Fmt.MiB, "0.0")} MB/s) von der Platte nach, weil Daten nicht mehr in den RAM passen. {paging.Describe()}");
        }

        if (s.Compressed > 1UL << 30)
            reasons.Add($"{Fmt.Bytes(s.Compressed)} liegen komprimiert im RAM. Windows war also schon unter Druck.");

        var limitable = groups.Where(g => g.Kind is AppKind.CacheGreedy or AppKind.VirtualMachine && g.TotalRam >= 512UL << 20)
                              .OrderByDescending(g => g.TotalRam).Take(4).ToList();
        if (limitable.Count > 0 && level >= Level.Hinweis)
            reasons.Add("Größte Reservierer, die du begrenzen kannst: " +
                        string.Join(", ", limitable.Select(g => $"{g.Name} {Fmt.Bytes(g.TotalRam)}")) + ". Tipps dazu im Tab „Apps“.");

        var leaks = groups.Where(g => g.LeakSuspect).ToList();
        if (leaks.Count > 0)
            reasons.Add("Stetig wachsend (möglicher Leak): " + string.Join(", ", leaks.Select(g => g.Name)) + ".");

        return new Verdict(level, headline, reasons);
    }

    private static double Nz(double v) => double.IsNaN(v) ? 0 : v;
}

public sealed record StallEstimate(double StallFraction, double ReadsPerSec, double LatencySeconds, bool LatencyMeasured)
{
    /// <summary>Wartezeit pro Sekunde in Millisekunden.</summary>
    public double StallMsPerSec => StallFraction * 1000;

    public string ImpactLabel => StallFraction switch
    {
        < 0.01 => "unmerklich",
        < 0.05 => "leicht (kurze Hänger beim Wechseln zwischen Programmen)",
        < 0.15 => "deutlich (spürbare Verzögerungen)",
        _ => "stark (System reagiert zäh)",
    };

    public string Describe() =>
        $"Programme warten dadurch ca. {Fmt.Num(StallMsPerSec)} ms pro Sekunde auf den Datenträger, Einbuße {ImpactLabel}" +
        (LatencyMeasured ? "." : " (Zugriffszeit geschätzt).");
}

/// <summary>Bewertet die Auslagerung: normal oder bremsend, und wie stark.</summary>
public static class PagingAnalyzer
{
    /// <summary>Unterhalb dieser Reserve gilt Nachladen als Auslagerung wegen RAM-Mangel.</summary>
    public const double LowRamFraction = 0.15;

    public static bool IsLowRam(SystemSample s) => s.AvailableFraction < LowRamFraction;

    /// <summary>Annahme für eine SSD, wenn die gemessene Zugriffszeit fehlt.</summary>
    public const double DefaultLatency = 0.0003;

    public static StallEstimate EstimateStall(History history)
    {
        double reads = history.Average(RamAnalyzer.RecentWindow, x => x.PageReadsPerSec);
        if (double.IsNaN(reads)) reads = 0;
        double lat = history.Average(RamAnalyzer.RecentWindow, x => x.DiskSecPerRead);
        bool measured = !double.IsNaN(lat) && lat > 0;
        if (!measured) lat = DefaultLatency;
        // Wartezeit pro Sekunde (über alle Threads summiert, daher bei 1 gedeckelt).
        double stall = Math.Min(1, reads * lat);
        return new StallEstimate(stall, reads, lat, measured);
    }

    public static Verdict Evaluate(SystemSample s, History history, PagefileInfo pf, IReadOnlyList<AppGroup> groups)
    {
        var stall = EstimateStall(history);
        double input = history.Average(RamAnalyzer.RecentWindow, x => x.PagesInputPerSec);
        double output = history.Average(RamAnalyzer.RecentWindow, x => x.PagesOutputPerSec);
        if (double.IsNaN(input)) input = 0;
        if (double.IsNaN(output)) output = 0;
        bool lowRam = IsLowRam(s);

        Level level;
        string headline;
        if (lowRam && stall.StallFraction >= 0.15)
        {
            level = Level.Kritisch;
            headline = "Nicht normal. Windows lagert ständig aus und bremst spürbar.";
        }
        else if (lowRam && (stall.StallFraction >= 0.05 || output > 200))
        {
            level = Level.Warnung;
            headline = "Grenzwertig. Es wird regelmäßig aus- und eingelagert.";
        }
        else if (lowRam && input > 50)
        {
            level = Level.Hinweis;
            headline = "Leichte Auslagerung, noch ohne spürbare Folgen.";
        }
        else
        {
            level = Level.Gut;
            headline = "Normal. Die Auslagerung kostet gerade keine Leistung.";
        }

        var r = new List<string>();
        r.Add($"Nachladen von der Platte (Hard Faults): {Fmt.Num(input)} Seiten/s ≈ {Fmt.Num(input * 4096 / Fmt.MiB, "0.0")} MB/s in {Fmt.Num(stall.ReadsPerSec)} Lesevorgängen/s (Mittel der letzten 60 s).");
        r.Add($"Wegschreiben: {Fmt.Num(output)} Seiten/s ≈ {Fmt.Num(output * 4096 / Fmt.MiB, "0.0")} MB/s.");
        if (lowRam)
            r.Add($"Geschätzter Leistungsverlust durch Auslagerung: {stall.Describe()}");
        else
        {
            r.Add("Leistungsverlust durch Auslagerung: keiner, es ist genug RAM frei.");
            if (input > 50)
                r.Add($"Das Nachladen ({Fmt.Num(stall.StallMsPerSec)} ms Wartezeit pro Sekunde) kommt vom Starten von Programmen bzw. Lesen von Dateien. Gemappte Dateien zählen auch als Hard Fault. Mit mehr RAM ginge das nicht schneller.");
        }

        if (pf.Files.Count > 0)
        {
            r.Add($"Auslagerungsdatei: {Fmt.Bytes(pf.Used)} belegt von {Fmt.Bytes(pf.Allocated)} (Spitze seit Start: {Fmt.Bytes(pf.Peak)}). " +
                  (pf.AutomaticManaged ? "Größe wird von Windows verwaltet. Das ist die empfohlene Einstellung." : "Feste Größe eingestellt."));
            if (pf.Used > 0 && !lowRam)
                r.Add("Belegter Platz in der Auslagerungsdatei ist normal. Windows schreibt lange ungenutzte Seiten vorsorglich hinaus, um RAM für den Cache frei zu machen. Entscheidend ist, wie oft sie wieder gebraucht werden (Hard Faults oben).");
        }
        else
        {
            r.Add("Keine Auslagerungsdatei gefunden. Ohne sie ist die Zusagegrenze gleich dem RAM. Programme können dann abstürzen, auch wenn noch RAM frei ist. Empfehlung: „Automatisch verwalten“ einschalten (sysdm.cpl → Erweitert → Leistung → Erweitert → Virtueller Arbeitsspeicher).");
        }

        if (s.Modified > 1UL << 30)
            r.Add($"{Fmt.Bytes(s.Modified)} geänderte Seiten warten aufs Wegschreiben. Viel davon bei knappem RAM heißt, dass gerade aktiv ausgelagert wird.");
        if (s.Compressed > 0)
            r.Add($"Komprimiert statt ausgelagert: {Fmt.Bytes(s.Compressed)} RAM halten komprimierte Seiten. Das ist schneller als die SSD.");

        var outside = groups.Where(g => g.NotResident >= 256UL << 20).OrderByDescending(g => g.NotResident).Take(5).ToList();
        if (outside.Count > 0)
            r.Add("Am meisten nicht im RAM (ausgelagert, komprimiert oder nie benutzt): " +
                  string.Join(", ", outside.Select(g => $"{g.Name} {Fmt.Bytes(g.NotResident)}")) + ".");

        var faulting = groups.Where(g => g.HardFaultsPerSec >= 20).OrderByDescending(g => g.HardFaultsPerSec).Take(5).ToList();
        if (faulting.Count > 0)
            r.Add("Lädt gerade am meisten nach: " + string.Join(", ", faulting.Select(g => $"{g.Name} {Fmt.Num(g.HardFaultsPerSec)}/s")) + ".");

        return new Verdict(level, headline, r);
    }
}
