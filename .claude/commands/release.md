Release erstellen fuer VoiceListener App.

Fuehre folgende Schritte der Reihe nach aus:

1. **Letzte Version ermitteln**: Lies die letzte Release-Version von GitHub mit `gh release list --limit 1`. Erhoehe die Minor-Version um 1 (z.B. v2.0.0 -> v2.1.0, v2.5.0 -> v2.6.0).

2. **Aenderungen committen & pushen**: Falls es uncommitted Changes gibt (`git status`), erstelle einen Commit mit einer passenden Message und pushe zu GitHub.

3. **APK bauen**: Fuehre `./gradlew assembleDebug` aus. Bei Fehler abbrechen.

4. **GitHub Release erstellen**: Erstelle ein neues Release mit der neuen Version und lade die APK hoch:
   ```
   gh release create vX.Y.0 app/build/outputs/apk/debug/app-debug.apk#VoiceListener-vX.Y.0.apk --title "vX.Y.0" --notes "Release notes hier"
   ```

5. **Ergebnis anzeigen**: Zeige die Release-URL und die neue Versionsnummer an.

Wichtig:
- Verwende immer den vollen Pfad fuer gh: `"/c/Program Files/GitHub CLI/gh.exe"`
- Die APK liegt nach dem Build unter: `app/build/outputs/apk/debug/app-debug.apk`
- Falls der User eine Release-Beschreibung als Argument uebergeben hat ($ARGUMENTS), verwende diese als --notes. Sonst fasse die Aenderungen seit dem letzten Release zusammen.
