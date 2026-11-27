using System.Windows.Forms;

namespace RamWaechter;

internal static class Program
{
    public static readonly string DataDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RamWaechter");

    /// <summary>
    /// Normal: Fenster öffnen. <c>--selftest &lt;ordner&gt;</c>: einige Runden messen, Bericht und
    /// Bilder aller Tabs speichern und beenden (für den Build auf einem Windows-Runner).
    /// </summary>
    [STAThread]
    private static int Main(string[] args)
    {
        string? selfTestDir = args.Length >= 1 && args[0] == "--selftest"
            ? Path.GetFullPath(args.Length >= 2 ? args[1] : "selftest")
            : null;

        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => LogError(e.Exception, selfTestDir);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => LogError(e.ExceptionObject as Exception, selfTestDir);

        if (selfTestDir is not null) Directory.CreateDirectory(selfTestDir);
        var form = new UI.MainForm { SelfTestDir = selfTestDir };
        Application.Run(form);
        return form.ExitCode;
    }

    private static void LogError(Exception? ex, string? selfTestDir)
    {
        try
        {
            string dir = selfTestDir ?? DataDir;
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "fehler.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  {ex}\n\n");
        }
        catch (Exception)
        {
        }
        if (selfTestDir is not null) Environment.Exit(3);
    }
}
