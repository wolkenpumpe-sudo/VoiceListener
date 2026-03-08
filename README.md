# Voice Listener App

Android Overlay-App zur Spracheingabe mit automatischer Textkorrektur. Transkription via **Groq Whisper**, optionale Korrektur via **Llama 3.3**. Der korrigierte Text wird automatisch in das fokussierte Textfeld eingefügt.

## Installation

### Voraussetzungen
- Android 8.0+ (API 26)
- Groq API Key ([console.groq.com](https://console.groq.com))

### Variante A: Über GitHub Actions (empfohlen)

1. **Repository erstellen** auf [github.com/new](https://github.com/new):
   - Name: `VoiceListener`, **Private**
   - "Create repository" klicken

2. **Dateien hochladen:**
   - Im Repository auf "uploading an existing file" klicken
   - Im Windows Explorer zu `C:\Users\Heiko\.gemini\antigravity\scratch\VoiceListener` navigieren
   - **ALLES** markieren (STRG+A), inkl. verstecktem `.github` Ordner
     (Explorer -> Ansicht -> "Ausgeblendete Elemente" aktivieren)
   - In den Browser ziehen und "Commit changes" klicken

3. **App bauen lassen:**
   - Repository -> **Actions** Tab -> Warten bis der Build grün wird (ca. 2-5 Min)

4. **APK herunterladen:**
   - Grünen Build-Eintrag anklicken -> **Artifacts** -> `app-debug` herunterladen
   - ZIP entpacken -> `app-debug.apk` aufs Handy übertragen

### Variante B: Direkte APK
Falls vorhanden, die `app-debug.apk` direkt aufs Handy übertragen.

## Einrichtung auf dem Handy

1. **APK installieren** (ggf. "Installation aus unbekannten Quellen" erlauben)
2. **App öffnen** und Berechtigungen erteilen:
   - **Audio** & **Benachrichtigungen**: Werden beim Start abgefragt
   - **Overlay** ("Über anderen Apps einblenden"): App leitet zu den Einstellungen
   - **Accessibility** ("Bedienungshilfe"): Android-Einstellungen -> Bedienungshilfen -> Voice Listener -> EIN
3. **API Key eingeben** und "Save & Start" drücken (Button oben oder unten)

## Bedienung des Overlay-Buttons

| Geste | Modus "Doppeltipp" | Modus "Long-Press" |
|---|---|---|
| **Einmalklick** | Clipboard in Historie erfassen | Clipboard in Historie erfassen |
| **Doppeltipp** | Aufnahme starten | Button verstecken |
| **Long-Press** | Button verstecken | Aufnahme starten |
| **Triple-Tap** | Menü öffnen/schließen | Menü öffnen/schließen |
| **Ziehen (Drag)** | Button verschieben | Button verschieben |
| **Vertikal-Swipe am Rand** | Lautstärke anpassen | Lautstärke anpassen |

### Aufnahme-Ablauf
1. Aufnahme starten (je nach Modus) -> Button wird **ROT**
2. Sprechen
3. Aufnahme stoppen -> Button wird **GELB** (Verarbeitung)
4. Text erscheint im fokussierten Textfeld (oder wird in die Zwischenablage kopiert, falls kein Textfeld fokussiert)

Aufnahmen unter 1 Sekunde werden automatisch verworfen.

### Button verstecken & wiederherstellen
- **Verstecken**: Je nach Modus per Long-Press oder Doppeltipp
  - Setzt den Button auf "Immer versteckt"
- **Wiederherstellen**: Auf die Benachrichtigung tippen
  - Setzt "Immer versteckt" zurück (Auto-Hide bleibt erhalten, falls aktiv)

## Extra-Menü (Triple-Tap)

| Funktion | Beschreibung |
|---|---|
| **Einstellungen** | Öffnet die App-Konfiguration |
| **Übersetzen** | Übersetzt Clipboard/Eingabe nach DE/EN/ES via Llama |
| **Zwischenablage** | Verlauf kopierter Texte (max. 50), mit Suche, Favoriten, Einfügen per Klick |
| **Marktdaten** | Live-Börsen-Widget (konfigurierbare Symbole & Intervall) |
| **askLlama** | Nächste Aufnahme als Frage an Llama statt Korrektur |
| **Sichtbarkeit** | Toggle: Auto / Immer sichtbar (setzt "Versteckt" zurück) |

## Einstellungen

- **Groq API Key**: Für Whisper-Transkription & Llama-Korrektur
- **System Prompt**: Anpassbarer Prompt für die Llama-Korrektur
- **Wörterbuch/Kontext**: Fachbegriffe, Namen für bessere Erkennung
- **Llama-Korrektur**: Ein/Aus
- **Aufnahme-Trigger**: Doppeltipp oder Long-Press
- **Button Größe**: 50% - 200%
- **Transparenz**: 20% - 100%
- **Button Farbe**: Lila, Blau, Rot, Grün, Schwarz
- **Auto-Hide**: Overlay nur bei fokussiertem Textfeld
- **Immer versteckt**: Nur über Notification steuerbar
- **Extra-Apps**: Einzeln aktivierbar (Übersetzen, Zwischenablage, Marktdaten, askLlama, EQS)
- **EQS Kontextmenü**: Text markieren -> "EQS" im Kontextmenü -> Marktdaten im Browser
- **Marktdaten-Symbole**: z.B. US500FU, USTECFU, DE40FU
- **Aktualisierungsintervall**: In Sekunden
- **Logs**: Ein/Aus, Kopieren, Löschen

## Autostart

Die App startet automatisch nach Geräte-Neustart (sofern Overlay-Berechtigung erteilt).

## Technologie

- **Sprache**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34
- **APIs**: Groq (Whisper Large V3 + Llama 3.3 70B)
- **Netzwerk**: Retrofit + OkHttp
- **UI**: Android Views + WindowManager Overlay
- **Services**: Foreground Service (Mikrofon) + Accessibility Service
