using System.Globalization;

namespace RamWaechter.Core;

/// <summary>Formatierung wie im Windows-Task-Manager (1 GB = 1024³ Bytes).</summary>
public static class Fmt
{
    public static readonly CultureInfo De = CultureInfo.GetCultureInfo("de-DE");
    public const double KiB = 1024, MiB = KiB * 1024, GiB = MiB * 1024;

    public static string Bytes(ulong bytes) => Bytes((double)bytes);

    public static string Bytes(double bytes)
    {
        if (double.IsNaN(bytes)) return "–";
        double abs = Math.Abs(bytes);
        if (abs >= GiB) return (bytes / GiB).ToString(abs >= 100 * GiB ? "0" : "0.0", De) + " GB";
        if (abs >= MiB) return (bytes / MiB).ToString("0", De) + " MB";
        if (abs >= KiB) return (bytes / KiB).ToString("0", De) + " KB";
        return bytes.ToString("0", De) + " B";
    }

    public static string Gb(double gb) => gb.ToString(gb >= 10 ? "0" : "0.#", De) + " GB";

    public static string Pct(double fraction) => (fraction * 100).ToString("0", De) + " %";

    public static string Num(double v, string format = "0") => double.IsNaN(v) ? "–" : v.ToString(format, De);

    public static string Duration(TimeSpan t)
    {
        if (t.TotalMinutes < 1) return $"{(int)t.TotalSeconds} s";
        if (t.TotalHours < 1) return $"{(int)t.TotalMinutes} min";
        if (t.TotalDays < 1) return $"{(int)t.TotalHours} h {t.Minutes} min";
        return $"{(int)t.TotalDays} Tage {t.Hours} h";
    }
}
