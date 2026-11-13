# Lärmwächter

Android-App, die im Hintergrund die Umgebungslautstärke misst und warnt, wenn es für das Gehör zu
laut wird – ähnlich der Geräusche-App der Apple Watch, aber auf geringen Akkuverbrauch ausgelegt.

## Wie sie Akku spart

Die App hört nicht dauerhaft zu. Sie nimmt alle 30 Sekunden eine halbe Sekunde lang eine Probe,
berechnet daraus den Pegel in dB(A) und schaltet das Mikrofon wieder ab. Dazwischen hält sie den
Prozessor nicht wach; ein exakter Alarm weckt das Telefon für die nächste Probe. Wird es laut
(10 dB unter der Warnschwelle), misst sie automatisch öfter.

Geschätzter Verbrauch (typische Werte, 4500-mAh-Akku, nicht am Gerät gemessen):

| Abstand der Messungen | Mikrofon aktiv | Akku pro Tag |
| --- | --- | --- |
| 30 s (Standard) | ca. 4 % der Zeit | ca. 1–2 % |
| 10 s | ca. 11 % der Zeit | ca. 4 % |
| 5 s (Prozessor bleibt wach) | ca. 22 % der Zeit | ca. 12 % |
| 5 min | unter 1 % der Zeit | ca. 0,1 % |

Die App zeigt die Schätzung für die aktuellen Einstellungen an. Den echten Verbrauch nennt Android
unter Einstellungen → Akku.

## Einstellbar

- Warnschwelle (70–110 dB(A)), wie lange der Lärm anhalten muss (sofort bis 10 min) und die Pause
  zwischen zwei Warnungen
- Vibration, Ton, Warnung bei 100 % Tagesdosis
- Abstand und Dauer der Messungen, „bei Lärm öfter messen“
- Kalibrierung (±20 dB), weil Handy-Mikrofone nicht geeicht sind

## Grenzen

- Die Werte sind eine Näherung. Ohne Kalibrierung sind einige dB Abweichung normal, in der
  Hosentasche misst das Mikrofon weniger, und ab etwa 110 dB übersteuert es.
- Zwischen zwei Messungen hört die App nichts. Ein einzelner Knall kann unbemerkt bleiben;
  anhaltender Lärm wird erkannt.
- Android zeigt bei jeder Messung kurz den grünen Mikrofon-Punkt.
- Nach einem Neustart des Telefons darf die App nicht von selbst starten (Android-Vorgabe für
  Mikrofon-Dienste). Sie zeigt dann eine Benachrichtigung zum Antippen.
- Es wird nichts aufgenommen oder gespeichert, und die App hat keine Internet-Berechtigung.

## Bauen und installieren

```
./gradlew assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`. Auf dem Telefon die Datei
öffnen und die Installation aus unbekannter Quelle erlauben. Auf GitHub baut der Workflow „Build“
bei jedem Push eine APK und hängt sie als Artefakt an.

Mindestens Android 8.0. Die App nutzt die Berechtigung `USE_EXACT_ALARM`; für eine Veröffentlichung
im Play Store müsste sie durch `SCHEDULE_EXACT_ALARM` ersetzt werden.
