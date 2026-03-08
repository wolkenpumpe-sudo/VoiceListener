@echo off
echo Erzwinge Berechtigungen fuer VoiceListener...

:: 1. Erlaube Restricted Settings (damit der Schalter nicht ausgegraut ist)
adb shell appops set com.example.voicelistener android:access_restricted_settings allow

:: 2. Erlaube Accessibility Service direkt (Hard Force)
adb shell pm grant com.example.voicelistener android.permission.BIND_ACCESSIBILITY_SERVICE

:: 3. Setze den Accessibility Service als aktiv (Achtung: Ueberschreibt evtl. andere, daher vorsichtig. Besser Schritt 1 & 2)
adb shell settings put secure enabled_accessibility_services com.example.voicelistener/.services.VoiceAccessibilityService

echo Fertig! Bitte App auf dem Handy prüfen.
pause
