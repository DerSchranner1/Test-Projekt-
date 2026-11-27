namespace RamWaechter.Core;

public enum AppKind
{
    Normal,
    /// <summary>Reserviert absichtlich viel RAM als Cache/Puffer (SQL Server, Datenbanken, JVM …).</summary>
    CacheGreedy,
    /// <summary>Virtuelle Maschinen: RAM gehört dem Gast, oft unsichtbar im Task-Manager.</summary>
    VirtualMachine,
    Development,
    Browser,
    System,
}

public sealed record KnownApp(
    string Key,
    string Name,
    AppKind Kind,
    string[] Processes,
    string Explanation,
    string Normal,
    string[] Tips)
{
    public string KindLabel => Kind switch
    {
        AppKind.CacheGreedy => "Reserviert RAM absichtlich",
        AppKind.VirtualMachine => "Virtuelle Maschine",
        AppKind.Development => "Entwicklung",
        AppKind.Browser => "Browser",
        AppKind.System => "Windows",
        _ => "",
    };

    public bool Matches(string processName)
    {
        foreach (var p in Processes)
        {
            if (p.EndsWith('*'))
            {
                if (processName.AsSpan().StartsWith(p.AsSpan(0, p.Length - 1), StringComparison.OrdinalIgnoreCase)) return true;
            }
            else if (processName.Equals(p, StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }
}

/// <summary>Bekannte Programme mit Erklärung, was normal ist, und Tipps zum Begrenzen.</summary>
public static class AppCatalog
{
    public const string SqlServerKey = "sqlserver";
    public const string HyperVKey = "hyperv";
    public const string WslKey = "wsl";
    public const string CompressionKey = "memcompression";

    public static readonly IReadOnlyList<KnownApp> All = new KnownApp[]
    {
        new(SqlServerKey, "SQL Server", AppKind.CacheGreedy, new[] { "sqlservr" },
            "SQL Server nimmt sich absichtlich so viel RAM, wie er darf. Der Standard ist praktisch unbegrenzt. " +
            "Damit hält er Datenseiten im Pufferpool. Freigegeben wird erst, wenn Windows meldet, dass der RAM knapp wird, " +
            "und auch dann nur langsam. Mit dem Recht „Lock Pages in Memory“ taucht dieser Speicher im Task-Manager gar nicht auf.",
            "Bis zum eingestellten Limit „max server memory“. Auf einem Entwickler-PC reichen meist 2–4 GB pro Instanz.",
            new[]
            {
                "Limit in SSMS: Rechtsklick auf den Server → Eigenschaften → Arbeitsspeicher → „Maximaler Serverarbeitsspeicher (in MB)“.",
                "Oder per T-SQL (wirkt sofort, ohne Neustart):\nEXEC sp_configure 'show advanced options', 1; RECONFIGURE;\nEXEC sp_configure 'max server memory (MB)', 4096; RECONFIGURE;",
                "Aktuellen Wert prüfen:\nSELECT value_in_use FROM sys.configurations WHERE name = 'max server memory (MB)';",
                "Brauchst du SQL Server nur ab und zu, stell den Dienst auf „Manuell“ (PowerShell als Admin):\nSet-Service MSSQLSERVER -StartupType Manual   (benannte Instanz: MSSQL$NAME)",
                "Page Life Expectancy über etwa 300 s heißt: SQL hat genug Puffer. Sehr hohe Werte bei kleinen Datenbanken heißen, dass das Limit runter kann.",
                "Jede Instanz (SQLEXPRESS, LocalDB, Entwickler-Instanz) ist eine eigene sqlservr.exe und braucht ihr eigenes Limit.",
            }),

        new("ssas", "SQL Server Analysis Services", AppKind.CacheGreedy, new[] { "msmdsrv" },
            "Analysis Services hält Modelle und Caches im RAM und wächst bis zu seinen Grenzwerten.",
            "Bis „Memory\\TotalMemoryLimit“ (Standard 80 % des RAM).",
            new[]
            {
                "In SSMS mit Analysis Services verbinden → Server-Eigenschaften → „Memory\\TotalMemoryLimit“ und „HardMemoryLimit“ senken (Werte unter 100 = Prozent des RAM).",
                "Dienst auf „Manuell“ stellen, wenn er nur selten gebraucht wird.",
            }),

        new("rider", "JetBrains Rider", AppKind.Development,
            new[] { "rider64", "rider", "rider.backend", "jetbrains.debugger.worker*", "jetbrains.resharper.*" },
            "Rider besteht aus zwei großen Prozessen. Die Oberfläche (rider64) läuft auf Java. Das Backend " +
            "(Rider.Backend, ReSharper auf .NET) hält die ganze Solution zur Analyse im Speicher. Java reserviert Heap " +
            "bis zum -Xmx-Limit und gibt ihn selten an Windows zurück.",
            "Kleine Solutions 1,5–3 GB, große (100+ Projekte) 4–8 GB für Oberfläche und Backend zusammen.",
            new[]
            {
                "Speicheranzeige einschalten: Rechtsklick auf die Statusleiste → „Memory Indicator“. Sie zeigt, wie voll der Java-Heap wirklich ist.",
                "Heap-Limit der Oberfläche: Help → Change Memory Settings. Nur senken, wenn der Indicator dauerhaft deutlich unter dem Limit bleibt. Zu wenig macht Rider langsam.",
                "Backend entlasten: Settings → Editor → Inspection Settings → „Enable solution-wide analysis“ aus. Das ist der größte Hebel bei großen Solutions.",
                "Nicht benötigte Projekte entladen (Solution Explorer → Rechtsklick → Unload Project) oder nur einen Solution Filter (.slnf) öffnen.",
                "File → Power Save Mode stoppt die Hintergrundanalyse, wenn du nur liest.",
                "Mehrere offene Solutions bedeuten mehrere Backends. Nicht genutzte Fenster schließen.",
                "Build-Prozesse (MSBuild, VBCSCompiler, dotnet) erscheinen separat unter „.NET-Build-Server“.",
            }),

        new("jetbrains", "JetBrains-IDE", AppKind.Development,
            new[] { "idea64", "pycharm64", "webstorm64", "clion64", "goland64", "datagrip64", "phpstorm64", "rustrover64", "rubymine64", "fleet" },
            "JetBrains-IDEs laufen auf Java. Der Heap wächst bis zum -Xmx-Limit und wird selten an Windows zurückgegeben. Dazu kommen Indizes für alle Projektdateien.",
            "1–4 GB je nach Projektgröße.",
            new[]
            {
                "Rechtsklick auf die Statusleiste → „Memory Indicator“ zeigt die echte Heap-Auslastung.",
                "Help → Change Memory Settings setzt das Heap-Limit.",
                "Große Ordner (node_modules, build, dist, Daten) per Rechtsklick → Mark Directory as → Excluded vom Indexieren ausnehmen.",
                "File → Power Save Mode, wenn du nur liest.",
            }),

        new("dotnetbuild", ".NET-Build-Server", AppKind.Development,
            new[] { "msbuild", "vbcscompiler", "dotnet", "microsoft.codeanalysis.languageserver", "rzls" },
            "MSBuild-Knoten und der Roslyn-Compilerserver (VBCSCompiler) bleiben nach einem Build einige Minuten im Speicher, damit der nächste Build schneller startet. „dotnet“ kann auch eine laufende .NET-App sein.",
            "Je Knoten 100–500 MB, Compilerserver bis 1–2 GB.",
            new[]
            {
                "Alle Build-Server sofort beenden: dotnet build-server shutdown",
                "Dauerhaft keine wiederverwendeten MSBuild-Knoten: Umgebungsvariable MSBUILDDISABLENODEREUSE=1 setzen (Builds starten dann etwas langsamer).",
                "Parallelität begrenzen: dotnet build -m:2 bzw. msbuild /m:2",
            }),

        new("visualstudio", "Visual Studio", AppKind.Development,
            new[] { "devenv", "servicehub.*", "microsoft.servicehub.*", "perfwatson2", "vshub", "msvsmon" },
            "Visual Studio verteilt sich auf devenv.exe und viele ServiceHub-Prozesse (Roslyn-Analyse, IntelliCode, Test-Explorer).",
            "1–4 GB, bei großen Solutions mehr.",
            new[]
            {
                "Tools → Options → Text Editor → C# → Advanced → „Run background code analysis for“ auf „Current document“ stellen.",
                "Solution Filter (.slnf) öffnen statt der ganzen Solution.",
                "Extensions → Manage Extensions: Erweiterungen deaktivieren, die du nicht brauchst.",
                "Help → Send Feedback → Report a Problem zeigt bei Bedarf, welche Komponente wächst.",
            }),

        new("vscode", "Visual Studio Code", AppKind.Development, new[] { "code", "code - insiders" },
            "VS Code ist eine Electron-App. Jede Erweiterung, jeder Sprachserver und der TypeScript-Server laufen als eigene Prozesse.",
            "300 MB – 1,5 GB, mit vielen Sprachservern mehr.",
            new[]
            {
                "Help → Open Process Explorer zeigt, welche Erweiterung wie viel RAM braucht.",
                "Erweiterungen pro Workspace deaktivieren (Zahnrad an der Erweiterung → Disable (Workspace)).",
                "files.watcherExclude und search.exclude für node_modules, bin, obj und Build-Ordner setzen.",
                "TypeScript begrenzen: typescript.tsserver.maxTsServerMemory (MB).",
            }),

        new(HyperVKey, "Hyper-V-VMs", AppKind.VirtualMachine, new[] { "vmwp", "vmms", "vmcompute" },
            "Der RAM von Hyper-V-VMs gehört keinem sichtbaren Prozess. vmwp.exe ist nur der kleine Verwaltungsprozess pro VM. " +
            "Der eigentliche Gast-RAM taucht im Task-Manager nirgends auf und zählt nur als „In Verwendung“. " +
            "RamWächter liest ihn über die Hyper-V-Leistungsindikatoren aus.",
            "Eine VM mit statischem RAM belegt ihren gesamten Startspeicher, sobald sie läuft, egal wie viel der Gast nutzt.",
            new[]
            {
                "Übersicht (PowerShell als Admin):\nGet-VM | Select Name, State, MemoryAssigned, MemoryDemand, DynamicMemoryEnabled",
                "Dynamischen Arbeitsspeicher nutzen (VM vorher ausschalten):\nSet-VMMemory -VMName 'Name' -DynamicMemoryEnabled $true -MinimumBytes 1GB -StartupBytes 2GB -MaximumBytes 6GB",
                "Oder im Hyper-V-Manager: Einstellungen der VM → Arbeitsspeicher → „Dynamischen Arbeitsspeicher aktivieren“.",
                "Nicht benötigte VMs speichern statt laufen lassen. Das gibt den RAM komplett frei:\nSave-VM -Name 'Name'",
                "Automatisch startende VMs finden:\nGet-VM | Select Name, AutomaticStartAction",
            }),

        new(WslKey, "WSL 2 / Docker / Sandbox (vmmem)", AppKind.VirtualMachine, new[] { "vmmem", "vmmemwsl", "vmmemcmzygote" },
            "vmmem ist der RAM der leichten Linux-VM von WSL 2. Docker Desktop und die Windows-Sandbox nutzen dieselbe Technik. " +
            "Linux verwendet freien RAM als Dateicache und gibt ihn ohne Einstellung kaum an Windows zurück. Deshalb wächst vmmem gern auf viele GB.",
            "Ohne Einstellung darf WSL bis zu 50 % des RAM nehmen (ältere Versionen 80 %).",
            new[]
            {
                "Obergrenze setzen in %UserProfile%\\.wslconfig:\n[wsl2]\nmemory=6GB\nswap=2GB",
                "Ungenutzten Cache automatisch zurückgeben (WSL 2.0 und neuer), in derselben Datei:\n[experimental]\nautoMemoryReclaim=gradual",
                "Änderungen wirken nach: wsl --shutdown",
                "Was in Linux den RAM braucht: in WSL „free -h“ und „ps aux --sort=-rss | head“",
                "Docker Desktop mit WSL-Backend richtet sich nach .wslconfig. Mit Hyper-V-Backend: Settings → Resources → Memory. Pro Container: docker stats",
            }),

        new("docker", "Docker Desktop", AppKind.Development, new[] { "docker desktop", "com.docker.*", "dockerd", "docker" },
            "Docker Desktop selbst (Oberfläche und Backend) ist klein. Die Container laufen in einer VM und erscheinen als vmmem bzw. Hyper-V-VM.",
            "Oberfläche und Dienste 200–600 MB, die Container zählen bei der VM.",
            new[]
            {
                "Settings → Resources → Advanced: Speicherlimit der VM (nur beim Hyper-V-Backend, beim WSL-Backend gilt .wslconfig).",
                "Settings → Resources → „Resource Saver“ hält die VM bei Leerlauf an.",
                "Speicher pro Container: docker stats. Begrenzen: docker run --memory=2g … bzw. mem_limit in docker-compose.",
            }),

        new("vmware", "VMware-VM", AppKind.VirtualMachine, new[] { "vmware-vmx", "vmware" },
            "Jede laufende VMware-VM ist ein vmware-vmx-Prozess, der den Gast-RAM hält.",
            "So viel RAM, wie der VM zugeteilt ist.",
            new[]
            {
                "RAM der VM verringern: VM → Settings → Memory.",
                "Pausieren (Suspend) gibt den RAM frei.",
            }),

        new("virtualbox", "VirtualBox-VM", AppKind.VirtualMachine, new[] { "virtualboxvm", "vboxheadless", "vboxsvc" },
            "Jede laufende VirtualBox-VM hält ihren zugeteilten Gast-RAM.",
            "So viel RAM, wie der VM zugeteilt ist.",
            new[]
            {
                "RAM der VM verringern: Ändern → System → Hauptspeicher.",
                "Zustand speichern statt laufen lassen gibt den RAM frei.",
            }),

        new("ollama", "Lokale KI-Modelle (Ollama/LM Studio)", AppKind.CacheGreedy,
            new[] { "ollama", "ollama_llama_server", "ollama app", "lm studio", "llama-server" },
            "Geladene Sprachmodelle liegen komplett im RAM bzw. Grafikspeicher. Ollama behält ein Modell nach der letzten Anfrage noch einige Minuten geladen.",
            "Etwa so groß wie die Modelldatei (z. B. 4–8 GB für ein 7–8B-Modell).",
            new[]
            {
                "Modell sofort entladen: ollama stop <modell>   (geladene Modelle: ollama ps)",
                "Kürzere Haltezeit: Umgebungsvariable OLLAMA_KEEP_ALIVE=1m",
                "Nur ein Modell gleichzeitig: OLLAMA_MAX_LOADED_MODELS=1",
            }),

        new("chrome", "Google Chrome", AppKind.Browser, new[] { "chrome" },
            "Chrome startet für Tabs, Erweiterungen und GPU eigene Prozesse. Der RAM verteilt sich auf viele chrome.exe.",
            "Ca. 100–300 MB pro aktivem Tab, 2–6 GB bei vielen Tabs sind üblich.",
            new[]
            {
                "Umschalt+Esc öffnet Chromes eigenen Task-Manager. Dort siehst du, welcher Tab oder welche Erweiterung wie viel braucht.",
                "Einstellungen → Leistung → „Arbeitsspeicher-Sparmodus“ legt inaktive Tabs schlafen.",
                "Erweiterungen ausmisten (chrome://extensions).",
                "Einstellungen → System → „Apps im Hintergrund ausführen, wenn Chrome geschlossen ist“ ausschalten.",
            }),

        new("edge", "Microsoft Edge", AppKind.Browser, new[] { "msedge" },
            "Edge verteilt Tabs, Erweiterungen und GPU auf viele msedge.exe-Prozesse.",
            "Ca. 100–300 MB pro aktivem Tab.",
            new[]
            {
                "Umschalt+Esc öffnet den Browser-Task-Manager.",
                "Einstellungen → System und Leistung → „Schlafende Tabs“ und „Effizienzmodus“.",
                "Dort auch „Startboost“ und „Erweiterungen und Apps im Hintergrund weiter ausführen“ ausschalten.",
            }),

        new("firefox", "Mozilla Firefox", AppKind.Browser, new[] { "firefox" },
            "Firefox nutzt mehrere Inhaltsprozesse für Tabs.",
            "Ca. 100–300 MB pro aktivem Tab.",
            new[]
            {
                "about:processes zeigt den RAM pro Tab und Erweiterung.",
                "about:memory → „Minimize memory usage“ gibt sofort Speicher frei.",
                "about:unloads entlädt Tabs manuell.",
            }),

        new("otherbrowser", "Browser", AppKind.Browser, new[] { "brave", "opera", "vivaldi", "arc" },
            "Chromium-Browser mit eigenem Prozess pro Tab.",
            "Ca. 100–300 MB pro aktivem Tab.",
            new[] { "Umschalt+Esc öffnet den Browser-Task-Manager.", "Tabs schlafen legen / Speichersparmodus in den Einstellungen aktivieren." }),

        new("webview2", "WebView2 (Web-Oberflächen anderer Apps)", AppKind.Normal, new[] { "msedgewebview2" },
            "Viele Apps (neues Teams, neues Outlook, Widgets, Copilot, Spiele-Launcher) zeigen ihre Oberfläche mit eingebettetem Edge an. Der RAM gehört der App, die WebView2 gestartet hat (siehe „Gestartet von“).",
            "Je App 100–500 MB.",
            new[] { "Die App, die dahintersteckt, beenden oder ihren Autostart abschalten (Task-Manager → Autostart-Apps)." }),

        new("teams", "Microsoft Teams", AppKind.Normal, new[] { "ms-teams", "teams" },
            "Teams ist eine Web-Oberfläche (WebView2 bzw. Electron) mit Video- und Chat-Komponenten.",
            "400 MB – 1,5 GB, in Meetings mehr.",
            new[]
            {
                "Das neue Teams (ms-teams.exe) braucht deutlich weniger als das klassische Teams.",
                "Einstellungen → Allgemein → „Teams automatisch starten“ aus, wenn du es nicht ständig brauchst.",
            }),

        new("electron", "Electron-App", AppKind.Normal,
            new[] { "discord", "slack", "spotify", "whatsapp", "signal", "notion", "obsidian", "figma", "postman", "github desktop", "1password" },
            "Electron-Apps bringen jeweils einen eigenen Chromium-Browser mit.",
            "200–800 MB pro App.",
            new[]
            {
                "Nicht benötigte Apps komplett beenden (Tray-Symbol → Beenden, nicht nur Fenster schließen).",
                "Autostart abschalten: Task-Manager → Autostart-Apps.",
            }),

        new("defender", "Microsoft Defender", AppKind.System, new[] { "msmpeng", "mpdefendercoreservice", "nissrv", "mssense" },
            "Der Virenscanner hält Signaturen im RAM und braucht beim Scannen mehr.",
            "150–500 MB, beim vollständigen Scan kurz mehr.",
            new[]
            {
                "Für Entwickler: Ordner mit Repos und Build-Ausgaben ausschließen oder ein Dev Drive (ReFS mit Leistungsmodus) nutzen. Das spart vor allem CPU und Datenträgerzugriffe.",
            }),

        new(CompressionKey, "Speicherkomprimierung", AppKind.System, new[] { "memory compression", "memcompression" },
            "Windows komprimiert selten genutzte RAM-Seiten, statt sie auf die Platte auszulagern. Das ist viel schneller als Auslagern und völlig normal.",
            "0 bis wenige hundert MB im Alltag. Mehrere GB heißen: Windows war oder ist knapp an RAM.",
            new[]
            {
                "Nicht abschalten: Ohne Komprimierung würde mehr auf die SSD ausgelagert.",
                "Status in PowerShell: Get-MMAgent",
            }),

        new("kernel", "Windows-Kern", AppKind.System,
            new[] { "system", "registry", "secure system", "smss", "csrss", "wininit", "services", "lsass", "winlogon", "fontdrvhost", "lsaiso" },
            "Grundlegende Windows-Prozesse. „Registry“ hält die Registrierung im RAM. „Secure System“ ist die virtualisierungsbasierte Sicherheit (Kernisolierung).",
            "Zusammen meist 200–600 MB.",
            new[]
            {
                "Hat „System“ deutlich über 1 GB, ist meist ein Treiber schuld. RAMMap oder poolmon (Windows Driver Kit) zeigen, welcher Treiber Pool-Speicher belegt.",
            }),

        new("svchost", "Windows-Dienste (svchost)", AppKind.System, new[] { "svchost" },
            "svchost.exe beherbergt Windows-Dienste, meist einen Dienst pro Prozess.",
            "Zusammen 0,5–2 GB, verteilt auf viele Prozesse.",
            new[]
            {
                "Welche Dienste drinstecken: Task-Manager → Details → Rechtsklick auf svchost.exe → „Zu Dienst(en) wechseln“.\nOder: tasklist /svc /fi \"imagename eq svchost.exe\"",
                "Ein einzelner svchost mit mehreren GB ist nicht normal. Den Dienst darin neu starten oder Windows Update/Treiber prüfen.",
            }),

        new("dwm", "Desktopfenster-Manager", AppKind.System, new[] { "dwm" },
            "dwm.exe zeichnet alle Fenster auf den Bildschirm.",
            "50–500 MB, mit mehreren hochauflösenden Monitoren mehr.",
            new[] { "Deutlich über 1 GB oder stetiges Wachstum weisen meist auf ein Problem im Grafiktreiber hin. Treiber aktualisieren." }),

        new("explorer", "Windows-Explorer", AppKind.System, new[] { "explorer" },
            "Taskleiste, Startmenü und Datei-Explorer.",
            "100–300 MB.",
            new[] { "Shell-Erweiterungen (Cloud-Sync, Kontextmenü-Tools) können ihn aufblähen. Neu starten: Task-Manager → Windows-Explorer → Neu starten." }),

        new("search", "Windows-Suche", AppKind.System, new[] { "searchindexer", "searchprotocolhost", "searchfilterhost", "searchhost" },
            "Der Suchindex erfasst Dateiinhalte und E-Mails.",
            "50–300 MB, beim Neuindexieren mehr.",
            new[] { "Indizierungsoptionen (Systemsteuerung) → große Ordner wie Repos, node_modules oder VMs herausnehmen." }),

        new("onedrive", "OneDrive", AppKind.Normal, new[] { "onedrive", "filecoauth" },
            "Synchronisiert Dateien und hält die Dateiliste im Speicher.",
            "50–300 MB, bei sehr vielen Dateien mehr.",
            new[] { "„Dateien bei Bedarf“ aktivieren und nicht benötigte Ordner von der Synchronisierung ausnehmen." }),

        new("office", "Microsoft Office", AppKind.Normal, new[] { "outlook", "olk", "winword", "excel", "powerpnt", "onenote", "msaccess" },
            "Office-Programme. Outlook hält Postfach-Caches im RAM.",
            "Je Programm 100–600 MB, Outlook mit großen Postfächern mehr.",
            new[] { "Outlook: Datei → Kontoeinstellungen → Exchange-Cache auf weniger Monate stellen.", "Add-Ins prüfen: Datei → Optionen → Add-Ins." }),

        new("java", "Java-Anwendung", AppKind.CacheGreedy, new[] { "java", "javaw" },
            "Java reserviert Heap bis zum -Xmx-Limit. Ohne Angabe sind das bis zu 25 % des RAM, und der Speicher wird selten zurückgegeben. Oft stecken Gradle- oder Kotlin-Daemons, Elasticsearch oder Minecraft dahinter.",
            "Hängt komplett von -Xmx ab.",
            new[]
            {
                "Gradle-Daemons beenden: gradle --stop. Begrenzen über org.gradle.jvmargs=-Xmx2g in gradle.properties.",
                "Elasticsearch: -Xms/-Xmx in config/jvm.options.d/*.options setzen.",
                "Allgemein: -Xmx2g in der Startkonfiguration bzw. JAVA_TOOL_OPTIONS=-Xmx2g.",
            }),

        new("node", "Node.js", AppKind.Development, new[] { "node", "bun", "deno" },
            "JavaScript-Laufzeit: Dev-Server, Build-Tools, Sprachserver.",
            "100 MB – 2 GB.",
            new[] { "Heap begrenzen: NODE_OPTIONS=--max-old-space-size=2048", "Nicht mehr benötigte Dev-Server (npm run dev, Watcher) beenden." }),

        new("postgres", "PostgreSQL", AppKind.CacheGreedy, new[] { "postgres", "pg_ctl" },
            "PostgreSQL reserviert shared_buffers fest und nutzt pro Abfrage zusätzlich work_mem.",
            "shared_buffers plus etwas pro Verbindung.",
            new[] { "In postgresql.conf: shared_buffers (z. B. 512MB auf einem Entwickler-PC) und work_mem senken, dann Dienst neu starten." }),

        new("mysql", "MySQL / MariaDB", AppKind.CacheGreedy, new[] { "mysqld", "mariadbd" },
            "Der InnoDB-Pufferpool wird beim Start reserviert und wächst bis zur eingestellten Größe.",
            "innodb_buffer_pool_size plus etwas pro Verbindung.",
            new[] { "In my.ini: innodb_buffer_pool_size=512M (Entwickler-PC), dann Dienst neu starten." }),

        new("mongodb", "MongoDB", AppKind.CacheGreedy, new[] { "mongod" },
            "Der WiredTiger-Cache nimmt standardmäßig etwa 50 % von (RAM − 1 GB).",
            "Bis zur Cache-Größe.",
            new[] { "In mongod.cfg: storage.wiredTiger.engineConfig.cacheSizeGB: 1" }),

        new("redis", "Redis / Memurai", AppKind.CacheGreedy, new[] { "redis-server", "memurai" },
            "Hält alle Daten im RAM.",
            "So viel wie die gespeicherten Daten.",
            new[] { "maxmemory 512mb und maxmemory-policy allkeys-lru in der Konfiguration setzen." }),

        new("adobe", "Adobe Creative Cloud", AppKind.CacheGreedy,
            new[] { "photoshop", "adobe premiere pro", "afterfx", "lightroom", "illustrator", "adobe media encoder" },
            "Adobe-Programme reservieren einen einstellbaren Prozentsatz des RAM für Bild- und Videodaten.",
            "Standard 70 % des RAM (Photoshop) bzw. großzügige Reserven (Premiere).",
            new[]
            {
                "Photoshop: Bearbeiten → Voreinstellungen → Leistung → „Von Photoshop verwenden lassen“ senken.",
                "Premiere/After Effects: Voreinstellungen → Arbeitsspeicher → „Für andere Anwendungen reservierter RAM“ erhöhen.",
            }),

        new("gamelaunchers", "Spiele-Launcher", AppKind.Normal,
            new[] { "steam", "steamwebhelper", "epicgameslauncher", "eadesktop", "battle.net", "ubisoftconnect", "galaxyclient" },
            "Launcher laufen im Hintergrund weiter und bringen meist eine Web-Oberfläche mit.",
            "200–800 MB.",
            new[] { "Autostart abschalten und den Launcher nur zum Spielen starten." }),
    };

    public static KnownApp? Match(string processName)
    {
        foreach (var app in All)
            if (app.Matches(processName)) return app;
        return null;
    }

    public static KnownApp Get(string key) => All.First(a => a.Key == key);

    /// <summary>Allgemeiner Hinweis für große, unbekannte Prozesse.</summary>
    public const string GenericLargeTip =
        "Großer Verbraucher ohne bekannte Ursache. Wächst der Wert stetig (Spalte Trend), deutet das auf ein Speicherleck hin. " +
        "Dann hilft Neustarten oder Aktualisieren des Programms. Bleibt er konstant, ist es vermutlich Absicht (Cache, geladene Daten).";
}
