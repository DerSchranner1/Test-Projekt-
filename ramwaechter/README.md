# RamWächter

Windows-Tool, das den Arbeitsspeicher verständlich macht. Es zeigt, wohin der RAM geht, ob er gerade
bremst, was davon normal ist, wie sich die Auslagerung auswirkt und ob sich ein Upgrade lohnt.
Programme, die sich absichtlich viel RAM nehmen (SQL Server, Hyper-V, WSL, Rider, Java, Datenbanken),
markiert es und sagt, wie man sie begrenzt.

## Herunterladen

Fester Link zur neuesten Version (immer dieselbe Adresse):

https://github.com/DerSchranner1/Test-Projekt-/releases/download/ramwaechter-latest/RamWaechter.exe

Eine Datei, keine Installation, Windows 10/11 64 Bit. Die EXE ist nicht signiert. Bei der
SmartScreen-Warnung auf „Weitere Informationen“ → „Trotzdem ausführen“ klicken.

## Was es zeigt

**Übersicht**
- Urteil „Bremst der RAM gerade?“ mit Begründung. Hohe Auslastung durch Standby-Cache gilt als normal,
  echter Mangel ist wenig verfügbarer RAM bei gleichzeitigem Nachladen von der Platte oder ein Commit
  nahe am Limit.
- Aufteilung des eingebauten RAM: Programme, virtuelle Maschinen, SQL Server (gesperrte Seiten),
  komprimiert, Kernel/Treiber, Dateicache, geändert, geteilt/sonstiges, Standby, frei,
  hardware-reserviert. Fährt man mit der Maus über einen Bereich, erscheint die Erklärung.
- Größte Verbraucher, Kennzahlen, Verlauf der letzten 10 Minuten.

**Apps**
- Prozesse zu Apps zusammengefasst (z. B. rider64 + Rider.Backend, alle chrome.exe), mit privatem
  RAM, zugesagtem Speicher, „nicht im RAM“, Hard Faults pro Sekunde und Trend.
- Farblich markiert: gelb = reserviert absichtlich (begrenzbar), lila = VM, rot = wächst stetig
  (möglicher Leak).
- RAM, den der Task-Manager nicht zeigt, wird mitgezählt: Gast-RAM klassischer Hyper-V-VMs und der
  SQL-Server-Pufferpool bei „Lock Pages in Memory“.
- Zu rund 40 bekannten Programmen: warum sie so viel brauchen, was normal ist und konkrete Schritte
  zum Begrenzen (T-SQL für `max server memory`, `.wslconfig`, `Set-VMMemory`, Rider-Einstellungen …).
  Erkennt außerdem, wenn SQL Server ohne Speicherlimit läuft.

**Auslagerung**
- Urteil, ob die Auslagerung normal ist, plus geschätzter Leistungsverlust: Lesevorgänge pro Sekunde
  × gemessene Lesezeit des Datenträgers = Wartezeit pro Sekunde.
- Auslagerungsdatei (Größe, belegt, Spitze, automatisch verwaltet?), Nachladen und Wegschreiben,
  geänderte und komprimierte Seiten.
- Welche Apps am meisten außerhalb des RAM haben und welche gerade nachladen.

**Hardware & Upgrade**
- Riegel pro Steckplatz (Größe, DDR-Typ, Bauform, Takt aktiv/maximal, Spannung, Hersteller,
  Teilenummer), freie Steckplätze, maximal unterstützter RAM, Mainboard.
- Kanalmodus: Single-/Dual-Channel, Flex-Modus bei ungleichen Riegeln, Hinweis auf XMP/EXPO, wenn
  der RAM unter seinem Takt läuft.
- Upgrade-Empfehlung aus dem höchsten gemessenen Bedarf (in Verwendung + ausgelagert) und der Zeit
  unter RAM-Druck. Das Ziel ist eine kaufbare Größe. Dazu kommen konkrete Varianten (gleiche Riegel
  ergänzen oder 2er-Kit ersetzen) und Warnungen (DDR5 mit 4 Riegeln, verlöteter RAM, BIOS-Maximum).
- Die Tageshöchstwerte werden 30 Tage lang in `%LOCALAPPDATA%\RamWaechter\tageswerte.json`
  gespeichert. So zählt ein ganzer Arbeitstag, nicht nur die aktuelle Sitzung. Nach einem
  RAM-Upgrade beginnt die Statistik neu.

„Bericht kopieren“ bzw. „Bericht speichern …“ erzeugt eine Textfassung von allem, z. B. für ein Forum.

## Woher die Werte kommen

| Wert | Quelle |
| --- | --- |
| Privater RAM, Commit, Hard Faults je Prozess | `NtQuerySystemInformation` (wie Process Explorer, ohne Adminrechte) |
| Standby, Frei, Geändert, Cache, Pools, Auslagerungsraten, Lesezeit | Leistungsindikatoren über PDH mit englischen Namen (funktioniert auf deutschem Windows) |
| Hyper-V-VMs | `\Hyper-V VM Vid Partition(*)\Physical Pages Allocated` |
| SQL Server | `SQLServer:Memory Manager` / `MSSQL$INSTANZ:…` (Total/Target Server Memory, Page Life Expectancy) |
| Riegel, Slots, Auslagerungsdatei | WMI (`Win32_PhysicalMemory`, `Win32_PhysicalMemoryArray`, `Win32_PageFileUsage`) |

## Grenzen

- „Geteilt & Sonstiges“ ist ein Rest: gemeinsam genutzte DLLs, gemappte Dateien, Seitentabellen und
  von Treibern gesperrter Speicher. Genauer schlüsselt das nur RAMMap auf.
- „Nicht im RAM“ enthält auch reservierten, aber nie benutzten Speicher. Das ist nicht automatisch
  ausgelagert.
- Die Leistungseinbuße ist eine Schätzung aus Zugriffen × Zugriffszeit. Hard Faults zählen auch das
  normale Laden von Programmdateien. Deshalb wertet RamWächter sie nur bei knappem RAM als Problem.
- Die Slot-Anzahl kommt aus dem BIOS und ist bei manchen Boards falsch. Vor dem Kauf im Handbuch
  prüfen.
- Ohne Adminrechte fehlen Pfade von Systemprozessen und eventuell die Hyper-V-Werte. Dafür gibt es
  „Als Administrator neu starten“.

## Bauen

```
dotnet test ramwaechter/tests/RamWaechter.Core.Tests
dotnet publish ramwaechter/src/RamWaechter -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -o out
```

Der Workflow „RamWächter“ baut bei jedem Push auf einem Windows-Runner und führt einen Selbsttest aus:
`RamWaechter.exe --selftest <ordner>` misst einige Runden und speichert Bericht und Bilder aller Tabs.
Danach ersetzt er die Datei hinter dem Download-Link. Die Analyse-Logik (`RamWaechter.Core`) ist
plattformunabhängig und getestet.
