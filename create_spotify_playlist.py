#!/usr/bin/env python3
"""
Creates a Spotify playlist with tracks from Apple Music's
"Salsa Clásica: Imprescindibles" / "Classic Salsa Essentials" playlist.

Usage:
  1. Go to https://developer.spotify.com/dashboard and create an app
  2. Set redirect URI to http://localhost:8888/callback
  3. Run: python3 create_spotify_playlist.py
  4. Enter your Client ID and Client Secret when prompted
  5. A browser window will open for Spotify login
  6. After login, the playlist will be created in your account
"""

import base64
import hashlib
import http.server
import json
import os
import secrets
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser

# --- Track list from Apple Music "Salsa Clásica: Imprescindibles" ---
TRACKS = [
    ("Héctor Lavoe", "El Cantante"),
    ("Rubén Blades", "Pedro Navaja"),
    ("Celia Cruz", "La Vida Es Un Carnaval"),
    ("El Gran Combo de Puerto Rico", "Un Verano en Nueva York"),
    ("Willie Colón", "Che Che Colé"),
    ("Frankie Ruiz", "La Rueda"),
    ("Joe Arroyo", "La Rebelión"),
    ("Dimensión Latina", "Llorarás"),
    ("Oscar D'León", "Llorarás"),
    ("Ismael Rivera", "Las Caras Lindas"),
    ("Pete Rodriguez", "I Like It Like That"),
    ("Ray Barretto", "El Watusi"),
    ("Richie Ray & Bobby Cruz", "Sonido Bestial"),
    ("Richie Ray & Bobby Cruz", "Juan en la Ciudad"),
    ("Roberto Roena", "Que Se Sepa"),
    ("Ismael Miranda", "Señor Sereno"),
    ("Larry Harlow", "El Paso de Encarnación"),
    ("Bobby Valentín", "El Jíbaro y la Naturaleza"),
    ("Tommy Olivencia", "Lobo Domesticado"),
    ("Cheo Feliciano", "Amada Mía, Amante Mío"),
    ("Willie Colón", "El Gran Varón"),
    ("Gilberto Santa Rosa", "Conciencia"),
    ("Gilberto Santa Rosa", "Que Alguien Me Diga"),
    ("Víctor Manuelle", "He Tratado"),
    ("Tito Rojas", "Siempre Seré"),
    ("Maelo Ruiz", "Te Va a Doler"),
    ("Eddie Santiago", "Lluvia"),
    ("Eddie Santiago", "Todo Empezó"),
    ("Lalo Rodríguez", "Ven Devórame Otra Vez"),
    ("Willie González", "No Voy a Llorar"),
    ("Jerry Rivera", "Amores Como el Nuestro"),
    ("Marc Anthony", "Vivir Mi Vida"),
    ("Marc Anthony", "Valió la Pena"),
    ("La India", "Ese Hombre"),
    ("La India", "Dicen Que Soy"),
    ("Celia Cruz", "Quimbara"),
    ("Celia Cruz", "Bemba Colorá"),
    ("Willie Colón", "Idilio"),
    ("Willie Colón", "Gitana"),
    ("Héctor Lavoe", "Mi Gente"),
    ("Héctor Lavoe", "Periódico de Ayer"),
    ("Héctor Lavoe", "Aguanilé"),
    ("Rubén Blades", "Decisiones"),
    ("Rubén Blades", "Plástico"),
    ("Fania All Stars", "Quítate Tú"),
    ("Fania All Stars", "Ponte Duro"),
    ("Tito Puente", "Oye Como Va"),
    ("Tito Puente", "Ran Kan Kan"),
    ("Johnny Pacheco", "Quítate Tú"),
    ("El Gran Combo de Puerto Rico", "Y No Hago Más Na'"),
    ("El Gran Combo de Puerto Rico", "Brujería"),
    ("El Gran Combo de Puerto Rico", "Me Liberé"),
    ("Oscar D'León", "Detalles"),
    ("Oscar D'León", "Calculadora"),
    ("Ismael Rivera", "El Incomprendido"),
    ("Cheo Feliciano", "Anacaona"),
    ("Bobby Cruz", "La Boda de Ella"),
    ("Tommy Olivencia", "Plante Bandera"),
    ("Roberto Roena", "Tu Loco Loco y Yo Tranquilo"),
    ("La Sonora Ponceña", "Fuego en el 23"),
    ("La Sonora Ponceña", "Hachero Pa' un Palo"),
    ("Grupo Niche", "Cali Pachanguero"),
    ("Grupo Niche", "Una Aventura"),
    ("Grupo Niche", "Busca por Dentro"),
    ("Fruko y Sus Tesos", "El Preso"),
    ("Joe Arroyo", "En Barranquilla Me Quedo"),
    ("Orquesta Guayacán", "Oiga, Mire, Vea"),
    ("Orquesta Guayacán", "Ay Amor Cuando Hablan las Miradas"),
    ("Son de Cali", "El Meneíto"),
    ("Tito Nieves", "I Like It Like That"),
    ("Tito Nieves", "De Mí Enamórate"),
    ("Tony Vega", "Esa Mujer"),
    ("Domingo Quiñones", "Señora de Madrugada"),
    ("Andy Montañez", "Casi Te Envidio"),
    ("Ismael Miranda", "Así Se Compone un Son"),
    ("Ray de la Paz", "Mi Libertad"),
    ("Willie Rosario", "Lluvia de Amor"),
    ("Tito Allen", "Conspiración"),
    ("Adalberto Santiago", "Quítate la Máscara"),
    ("Yuri Buenaventura", "Salsa"),
    ("Guayacán Orquesta", "Invierno en Primavera"),
    ("Nino Segarra", "Ese Loco Soy Yo"),
    ("Lalo Rodríguez", "Un Nuevo Comienzo"),
    ("Frankie Ruiz", "Deseándote"),
    ("Frankie Ruiz", "Tu Con El"),
    ("Frankie Ruiz", "Mi Libertad"),
    ("Héctor Lavoe", "Rompe Saragüey"),
    ("Celia Cruz & Willie Colón", "Usted Abusó"),
    ("Rubén Blades", "Buscando Guayaba"),
    ("Ismael Rivera", "Quítate de la Vía Perico"),
    ("El Gran Combo de Puerto Rico", "No Hay Cama Pa' Tanta Gente"),
    ("Willie Colón", "Calle Luna, Calle Sol"),
    ("Héctor Lavoe", "Todo Tiene Su Final"),
    ("La Sonora Ponceña", "Quítate la Máscara"),
    ("Cheo Feliciano", "El Ratón"),
    ("Bobby Valentín", "Pirata de Mar y Tierra"),
]

PLAYLIST_NAME = "Salsa Clásica: Imprescindibles"
PLAYLIST_DESCRIPTION = (
    "Klassische Salsa-Essentials - migriert von Apple Music. "
    "Die historischen Songs, die Salsa seinen unverkennbaren Geschmack gaben."
)

REDIRECT_URI = "http://localhost:8888/callback"
SCOPES = "playlist-modify-public playlist-modify-private"
AUTH_URL = "https://accounts.spotify.com/authorize"
TOKEN_URL = "https://accounts.spotify.com/api/token"
API_BASE = "https://api.spotify.com/v1"

auth_code = None
auth_event = threading.Event()


class CallbackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global auth_code
        query = urllib.parse.urlparse(self.path).query
        params = urllib.parse.parse_qs(query)
        if "code" in params:
            auth_code = params["code"][0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(
                b"<html><body><h2>Erfolgreich! Du kannst dieses Fenster schliessen.</h2></body></html>"
            )
            auth_event.set()
        else:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"Authorization failed.")
            auth_event.set()

    def log_message(self, format, *args):
        pass  # suppress logs


def get_auth_code(client_id: str) -> str:
    """Open browser for Spotify OAuth and capture the auth code."""
    params = {
        "client_id": client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPES,
    }
    url = f"{AUTH_URL}?{urllib.parse.urlencode(params)}"

    server = http.server.HTTPServer(("localhost", 8888), CallbackHandler)
    server_thread = threading.Thread(target=server.handle_request)
    server_thread.start()

    print(f"\nOeffne Browser fuer Spotify-Login...")
    print(f"Falls der Browser sich nicht oeffnet, oeffne diese URL manuell:\n{url}\n")
    webbrowser.open(url)

    auth_event.wait(timeout=120)
    server.server_close()

    if not auth_code:
        print("Fehler: Keine Autorisierung erhalten.")
        sys.exit(1)
    return auth_code


def get_access_token(client_id: str, client_secret: str, code: str) -> str:
    """Exchange auth code for access token."""
    data = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": REDIRECT_URI,
    }).encode()

    credentials = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    req = urllib.request.Request(
        TOKEN_URL,
        data=data,
        headers={
            "Authorization": f"Basic {credentials}",
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())["access_token"]


def spotify_api(method: str, endpoint: str, token: str, body=None):
    """Make authenticated Spotify API request."""
    url = f"{API_BASE}{endpoint}" if endpoint.startswith("/") else endpoint
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    })
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read()) if resp.status != 204 else None


def get_user_id(token: str) -> str:
    return spotify_api("GET", "/me", token)["id"]


def create_playlist(token: str, user_id: str) -> str:
    result = spotify_api("POST", f"/users/{user_id}/playlists", token, {
        "name": PLAYLIST_NAME,
        "description": PLAYLIST_DESCRIPTION,
        "public": True,
    })
    print(f"\nPlaylist erstellt: {result['external_urls']['spotify']}")
    return result["id"]


def search_track(token: str, artist: str, title: str) -> str | None:
    """Search for a track on Spotify. Returns track URI or None."""
    query = urllib.parse.quote(f"artist:{artist} track:{title}")
    result = spotify_api("GET", f"/search?q={query}&type=track&limit=5", token)
    tracks = result.get("tracks", {}).get("items", [])
    if tracks:
        return tracks[0]["uri"]

    # Fallback: simpler search
    query = urllib.parse.quote(f"{artist} {title}")
    result = spotify_api("GET", f"/search?q={query}&type=track&limit=5", token)
    tracks = result.get("tracks", {}).get("items", [])
    if tracks:
        return tracks[0]["uri"]

    return None


def add_tracks(token: str, playlist_id: str, uris: list[str]):
    """Add tracks to playlist in batches of 100."""
    for i in range(0, len(uris), 100):
        batch = uris[i:i + 100]
        spotify_api("POST", f"/playlists/{playlist_id}/tracks", token, {"uris": batch})


def main():
    print("=" * 60)
    print("  Salsa Clasica: Imprescindibles -> Spotify Playlist")
    print("=" * 60)
    print()
    print("Du brauchst eine Spotify Developer App:")
    print("  https://developer.spotify.com/dashboard")
    print("  Redirect URI: http://localhost:8888/callback")
    print()

    client_id = os.environ.get("SPOTIFY_CLIENT_ID") or input("Spotify Client ID: ").strip()
    client_secret = os.environ.get("SPOTIFY_CLIENT_SECRET") or input("Spotify Client Secret: ").strip()

    if not client_id or not client_secret:
        print("Fehler: Client ID und Client Secret werden benoetigt.")
        sys.exit(1)

    # OAuth flow
    code = get_auth_code(client_id)
    print("Autorisierung erhalten! Hole Access Token...")
    token = get_access_token(client_id, client_secret, code)
    print("Access Token erhalten!")

    # Get user info
    user_id = get_user_id(token)
    print(f"Eingeloggt als: {user_id}")

    # Create playlist
    playlist_id = create_playlist(token, user_id)

    # Search and add tracks
    print(f"\nSuche {len(TRACKS)} Tracks auf Spotify...")
    found_uris = []
    not_found = []

    for i, (artist, title) in enumerate(TRACKS, 1):
        uri = search_track(token, artist, title)
        if uri:
            found_uris.append(uri)
            print(f"  [{i}/{len(TRACKS)}] OK: {artist} - {title}")
        else:
            not_found.append((artist, title))
            print(f"  [{i}/{len(TRACKS)}] NICHT GEFUNDEN: {artist} - {title}")
        time.sleep(0.1)  # Rate limit

    # Add found tracks to playlist
    if found_uris:
        add_tracks(token, playlist_id, found_uris)
        print(f"\n{len(found_uris)} Tracks zur Playlist hinzugefuegt!")

    if not_found:
        print(f"\n{len(not_found)} Tracks nicht gefunden:")
        for artist, title in not_found:
            print(f"  - {artist} - {title}")

    print(f"\nFertig! {len(found_uris)}/{len(TRACKS)} Tracks erfolgreich hinzugefuegt.")


if __name__ == "__main__":
    main()
