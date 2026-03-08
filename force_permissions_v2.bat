@echo off
echo Versuche Accessibility Service ueber Secure Settings zu aktivieren...

:: 1. Restricted Settings erlauben (das hat beim letzten Mal evtl. geklappt)
adb shell appops set com.example.voicelistener android:access_restricted_settings allow

:: 2. Accessibility Enable (Generell einschalten)
adb shell settings put secure accessibility_enabled 1

:: 3. Unseren Service HINZUFUEGEN (enabled_accessibility_services)
:: Achtung: Wir lesen erst aus, was da ist, aber da es dein Dev-Handy ist, setzen wir es hart.
:: Der String muss exakt so sein: package/klasse
adb shell settings put secure enabled_accessibility_services com.example.voicelistener/.services.VoiceAccessibilityService

echo.
echo Wenn keine Fehlermeldung kam, sollte der Service jetzt AN sein.
echo Bitte App neu starten und testen.
pause
