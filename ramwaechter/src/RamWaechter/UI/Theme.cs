using System.Drawing;
using RamWaechter.Core;

namespace RamWaechter.UI;

internal static class Theme
{
    public static readonly Color Page = Color.FromArgb(243, 244, 246);
    public static readonly Color Card = Color.White;
    public static readonly Color Border = Color.FromArgb(226, 228, 233);
    public static readonly Color Text = Color.FromArgb(31, 41, 55);
    public static readonly Color Muted = Color.FromArgb(107, 114, 128);
    public static readonly Color Accent = Color.FromArgb(37, 99, 235);
    public static readonly Color Grid = Color.FromArgb(236, 238, 242);

    public static readonly Font Base = new("Segoe UI", 9.5f);
    public static readonly Font Small = new("Segoe UI", 8.5f);
    public static readonly Font Bold = new("Segoe UI Semibold", 9.5f);
    public static readonly Font Value = new("Segoe UI Semibold", 11f);
    public static readonly Font Headline = new("Segoe UI Semibold", 13f);
    public static readonly Font Title = new("Segoe UI Semibold", 20f);
    public static readonly Font Mono = new("Consolas", 9.5f);

    public static Color LevelColor(Level l) => l switch
    {
        Level.Gut => Color.FromArgb(22, 163, 74),
        Level.Hinweis => Color.FromArgb(202, 138, 4),
        Level.Warnung => Color.FromArgb(234, 88, 12),
        _ => Color.FromArgb(220, 38, 38),
    };

    public static Color SegmentColor(Segment s) => s switch
    {
        Segment.Programme => Color.FromArgb(37, 99, 235),
        Segment.VirtuelleMaschinen => Color.FromArgb(124, 58, 237),
        Segment.SqlGesperrt => Color.FromArgb(219, 39, 119),
        Segment.Komprimiert => Color.FromArgb(8, 145, 178),
        Segment.KernelTreiber => Color.FromArgb(71, 85, 105),
        Segment.Dateicache => Color.FromArgb(20, 184, 166),
        Segment.Geaendert => Color.FromArgb(245, 158, 11),
        Segment.Sonstiges => Color.FromArgb(148, 163, 184),
        Segment.Standby => Color.FromArgb(167, 233, 200),
        Segment.Frei => Color.FromArgb(229, 231, 235),
        _ => Color.FromArgb(203, 206, 212),
    };

    public static Color KindRowColor(AppKind k, bool leak) =>
        leak ? Color.FromArgb(254, 226, 226)
        : k switch
        {
            AppKind.CacheGreedy => Color.FromArgb(254, 243, 199),
            AppKind.VirtualMachine => Color.FromArgb(237, 233, 254),
            _ => Color.White,
        };

    public static Level ToLevel(UpgradeNeed n) => n switch
    {
        UpgradeNeed.KeinBedarf => Level.Gut,
        UpgradeNeed.Optional => Level.Hinweis,
        UpgradeNeed.Empfohlen => Level.Warnung,
        _ => Level.Kritisch,
    };
}
