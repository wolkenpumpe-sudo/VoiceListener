# Voice Listener App - Installationsanleitung

Diese Anleitung erklärt dir Schritt für Schritt, wie du aus den erstellten Dateien eine funktionierende App für dein Android-Handy machst, ohne Programmierkenntnisse.

## Schritt 1 & 2: Upload direkt über den Browser (ohne Zusatzprogramme)

Wenn du nichts installieren möchtest, kannst du die Dateien direkt auf der GitHub-Webseite hochladen.

1.  **Repository erstellen:**
    *   Gehe auf [github.com/new](https://github.com/new).
    *   Name: `VoiceListener`.
    *   **WICHTIG**: Wähle **Private** (Privat) aus.
    *   Klicke auf **Create repository**.

2.  **Dateien hochladen:**
    *   Du siehst nun eine Seite mit viel Text/Code. Klicke auf den Link in der Zeile:
        *"...or create a new repository on the command line or **uploading an existing file**"* 
        (Klicke auf "uploading an existing file").
    *   Öffne nun deinen Windows Datei-Explorer und gehe in den Ordner:
        `C:\Users\Heiko\.gemini\antigravity\scratch\VoiceListener`
    *   Markiere **ALLES** in diesem Ordner (Drücke `STRG + A`), auch die versteckten Ordner wie `.github` falls sichtbar (wenn nicht, nicht schlimm, aber wichtig: `.github` muss mit. Falls du `.github` nicht siehst im Explorer: Klicke im Explorer oben auf "Ansicht" -> Haken bei "Ausgeblendete Elemente").
    *   Ziehe alle markierten Dateien und Ordner mit der Maus in das graue Feld im Browser ("Drag files here to add them to your repository").
    *   Warte, bis alle Dateien geladen sind.
    *   Scrolle nach unten und klicke auf den grünen Button **Commit changes**.

*Hinweis: Wenn der Ordner `.github` nicht mit hochgeladen wird, funktioniert der automatische Builder nicht. Stelle sicher, dass du ihn im Explorer siehst und mit markierst.*

## Schritt 3: Die App bauen lassen

Jetzt arbeitet unser "Roboter" automatisch.

1.  Gehe auf die [GitHub Webseite](https://github.com/), logge dich ein und klicke rechts oben auf dein Profilbild -> **Your repositories** -> **VoiceListener**.
2.  Klicke oben in der Leiste auf den Reiter **Actions**.
3.  Du solltest dort einen Eintrag sehen ("Initial commit" oder similar), hinter dem sich ein gelber oder grüner Kreis dreht.
4.  Warte ca. 2-5 Minuten.
5.  Wenn der Kreis **grün** wird (✅), ist die App fertig.

## Schritt 4: App herunterladen (APK)

1.  Klicke in der Liste bei **Actions** auf den obersten, erfolgreichen (grünen) Eintrag (z.B. "Initial commit" oder "Android Build").
2.  Scrolle auf der neuen Seite nach unten zum Bereich **Artifacts**.
3.  Dort siehst du **app-debug**. Klicke darauf.
4.  Eine ZIP-Datei wird heruntergeladen. Entpacke sie auf deinem PC. Darin ist die Datei `app-debug.apk`.
5.  Sende diese Datei an dein Handy (z.B. per USB-Kabel, Google Drive, oder Email an dich selbst).

## Schritt 5: Installation & Einrichtung auf dem Handy

1.  Tippe auf dem Handy auf die `app-debug.apk`.
2.  Falls gefragt, erlaube "Installation aus unbekannten Quellen" (da wir die App nicht über den Play Store laden).
3.  Öffne die App **"VoiceListener"**.

### WICHTIGE EINSTELLUNGEN (Einmalig)
Damit die App über anderen Apps schweben und schreiben kann, musst du ihr Rechte geben. Die App wird dich teilweise fragen, aber hier ist der manuelle Weg, falls es klemmt:

1.  **API Key speichern:**
    *   Kopiere deinen **Groq API Key** in das Textfeld der App und drücke "Save & Start".
2.  **Overlay Berechtigung:**
    *   Die App sollte dich zu den Einstellungen leiten ("Über anderen Apps einblenden"). Suche "VoiceListener" in der Liste und schalte es **EIN**.
3.  **Accessibility Service (Bedienungshilfe):**
    *   Gehe in die Android-Einstellungen -> **Bedienungshilfen** (Accessibility).
    *   Suche unter "Heruntergeladene Apps" oder direkt in der Liste nach **VoiceAccessibilityService**.
    *   Schalte ihn **EIN**. (Android wird eine Warnung zeigen, da dieser Service sehr mächtig ist und Text lesen/schreiben darf. Bestätige dies, da es ja deine eigene App ist).

## Benutzung

1.  Ein kleiner **Mikrofon-Knopf** schwebt nun auf deinem Bildschirm am Rand.
2.  Tippe in irgendeiner App (WhatsApp, Browser, Notizen) in ein Textfeld, sodass die Tastatur aufgeht.
3.  Halte den Mikrofon-Knopf **GEDRÜCKT** und sprich deinen Text.
    *   *Knopf wird ROT*: Aufnahme läuft.
4.  Lass den Knopf **LOS**.
    *   *Knopf wird GELB*: Verarbeitung läuft (Verschicken an Groq -> Korrektur).
5.  Nach kurzer Zeit erscheint dein perfekter Text wie von Geisterhand im Textfeld!

Viel Spaß!
