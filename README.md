

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
