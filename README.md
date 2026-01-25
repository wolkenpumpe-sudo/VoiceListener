# Voice Listener App - Installationsanleitung

Diese Anleitung erklärt dir Schritt für Schritt, wie du aus den erstellten Dateien eine funktionierende App für dein Android-Handy machst, ohne Programmierkenntnisse.

## Schritt 1: GitHub Vorbereitung
Wir nutzen GitHub als unseren "Bau-Roboter" (GitHub Actions), der den Code für uns in eine App (APK) verwandelt.

1.  **GitHub Account:** Falls du noch keinen hast, registriere dich kostenlos auf [github.com](https://github.com/).
2.  **GitHub Desktop installieren:** Das ist ein einfaches Fenster-Programm für Windows, um Dateien zu GitHub zu senden.
    *   Lade es hier herunter: [desktop.github.com](https://desktop.github.com/)
    *   Installiere und starte es. Logge dich mit deinem GitHub-Account ein.

## Schritt 2: Code "hochladen" (Pushen)

1.  Öffne **GitHub Desktop**.
2.  Gehe oben links auf **File** -> **Add Local Repository...**
3.  Klicke auf **Choose...** und navigiere zu diesem Ordner (kopiere den Pfad in die Adresszeile):  
    `C:\Users\Heiko\.gemini\antigravity\scratch\VoiceListener`
4.  Klicke **Add Repository**.
5.  GitHub Desktop wird fragen: "This directory does not appear to be a Git repository. Would you like to create one here?" -> Klicke **Create a repository**.
6.  Gib bei "Name" `VoiceListener` ein (falls nicht schon da).
7.  WICHTIG: Klicke unten auf **Create Repository**.
8.  Jetzt erscheint oben ein blauer Button **Publish repository**. Klicke darauf.
9.  **Dier wichtigste Schritt:**
    *   Setze den Haken bei **Keep this code private**. (Das sorgt dafür, dass NIEMAND außer dir den Code oder die App sehen kann).
    *   Klicke auf **Publish Repository**.

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
