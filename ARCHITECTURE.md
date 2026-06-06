# Rufzeichen-Sucher – Architekturdokumentation

Dieses Dokument beschreibt die vollständige technische Architektur der Anwendung: Datenquellen, Datenmodell, Komponenten, UI-Aufbau, Persistenz und Performance-Anforderungen.

---

## 1. Projektübersicht

**Rufzeichen-Sucher** ist eine Desktop-Applikation zur Suche und Anzeige von deutschen Amateurfunk-Rufzeichen aus der offiziellen Liste der Bundesnetzagentur (BNetzA). Die App ergänzt diese Daten mit DMR-IDs aus der weltweiten RadioID-Datenbank und bietet geografische Visualisierung sowie Statistiken.

### Kernzweck
- Offline-Suche in ~70.000 deutschen Amateurfunk-Rufzeichen
- Anzeige von Lizenzklasse, Name, Adresse, Nebenstandort
- DMR-ID-Lookup pro Rufzeichen
- Geografische Verortung (Karte) pro Rufzeichen
- Statistische Auswertungen der Gesamtdatenbank
- Automatisches Aktualisieren der Daten aus dem Internet

---

## 2. Datenquellen

### 2.1 Rufzeichenliste (Primärdatenquelle)
| Eigenschaft | Wert |
|---|---|
| Quelle | Bundesnetzagentur |
| URL | `https://data.bundesnetzagentur.de/Bundesnetzagentur/SharedDocs/Downloads/DE/Sachgebiete/Telekommunikation/Unternehmen_Institutionen/Frequenzen/Amateurfunk/Rufzeichenliste/rufzeichenliste_afu.pdf` |
| Format | PDF (mehrspaltig, tabellarisch) |
| Umfang | ca. 70.000 Einträge |
| Aktualisierung | Alle 7 Tage automatisch |
| Fallback | Lokale Kopie im Bundle (`rufzeichenliste_afu.pdf`) |

### 2.2 DMR-Datenbank
| Eigenschaft | Wert |
|---|---|
| Quelle | RadioID.net |
| URL | `https://radioid.net/static/user.csv` |
| Format | CSV |
| Spalten | `RADIO_ID, CALLSIGN, FIRST_NAME, LAST_NAME, CITY, STATE, COUNTRY` |
| Umfang | ca. 306.000 Zeilen, ~16 MB |
| Aktualisierung | Alle 30 Tage automatisch |
| Fallback | Lokale Kopie im Bundle (`user.csv`) |

### 2.3 Postleitzahlen-Datenbank (gebündelt)
| Eigenschaft | Wert |
|---|---|
| Datei | `zipcodes.de.json` |
| Format | JSON-Array |
| Felder pro Eintrag | `zipcode` (String), `state` (String), `latitude` (String), `longitude` (String) |
| Umfang | ~12.000 PLZ-Einträge für Deutschland |
| Verwendung | Bundesland-Zuordnung und Koordinaten für Statistik-Heatmap |

### 2.4 Bevölkerungsdaten (gebündelt)
| Eigenschaft | Wert |
|---|---|
| Datei | `state_population.json` |
| Format | JSON-Array |
| Felder | `state` (String), `population` (int) |
| Umfang | 16 Bundesländer |
| Verwendung | Kennzahl „Funkamateure je 10.000 Einwohner" |

---

## 3. Datenmodell

### 3.1 `CallsignEntry` (Kernobjekt)
```
callsign          String    – Rufzeichen (z.B. "DL1ABC"), eindeutiger Schlüssel
licenseClass      String    – Lizenzklasse: "A", "E", "N" oder seltene andere
name              String    – Vollständiger Name des Inhabers
address           String?   – Hauptanschrift (null wenn Widerspruch gem. § 3 Abs. 4 AFuV)
secondaryLocation String?   – Nebenstandort / Klubstation (optional)
city              String?   – Abgeleiteter Ortsname aus address (nach 5-stelliger PLZ)
```

**Wichtig:** `address` ist bewusst nullable. Wenn ein Inhaber widersprochen hat, fehlt die Adresse komplett – das ist rechtlich vorgesehen und muss in der UI klar kommuniziert werden.

### 3.2 PDF-Format der BNetzA-Rufzeichenliste

Das PDF ist mehrspaltig und tabellarisch. Jeder Eintrag hat folgendes Rohformat nach Textextraktion:

```
RUFZEICHEN, KLASSE, NAME; STRASSE PLZ ORT; NEBENSTANDORT
```

Beispiele:
```
DL1ABC, A, Max Mustermann; Musterstraße 1; 12345 Musterstadt
DA6VA, A, Klaus Müller
DH9XY, E, Maria Schmidt; Bergweg 5; 54321 Bergheim; Antennenstandort
```

**Besonderheiten beim Parsing (KRITISCH für korrekte Implementierung):**
1. **Mehrspaltig:** Apache PDFBox liefert manchmal mehrere Tabelleneinträge in einer Textzeile zusammengeführt, z.B.: `"DA6VA, A, Klaus Müller DN9JCV, N, Juan Carlos Vazquez"`
2. **Zeilenumbrüche in Adressen:** Lange Adressen können über mehrere Rohzeilen gehen
3. **Seitenheader/-footer als Störtext:** `"Bundesnetzagentur"`, `"Rufzeichenliste"`, `"Stand:"`, `"Seite N"`, einzelne Seitenzahlen müssen herausgefiltert werden
4. **Unicode-Artefakte:** Soft-Hyphens (`­`), geschützte Leerzeichen (` `) müssen normalisiert werden
5. **Stand-Datum:** Das Deckblatt enthält das Ausgabedatum im Format `"vom 18. Mai 2026"` – muss geparst und gespeichert werden

**Rufzeichen-Regex:** `^D[A-PR-Z][0-9][A-Z0-9]{1,9},\s*[A-Z]{1,2}\s*,`

**Vollständiger Datensatz-Regex:** `^(D[A-PR-Z][0-9][A-Z0-9]{1,9}),\s*([A-Z]{1,2}),\s*(.+)$`

**Parsing-Algorithmus (sequenziell, dann parallel):**
1. Text seitenweise aus PDF extrahieren und zusammenführen
2. Unicode normalisieren
3. Leerzeilen und Noise-Zeilen entfernen (Regex: `^\s*(?:bundesnetzagentur|rufzeichenliste|stand\s*:|seite\s+\d+|\d{1,4})\s*$`)
4. Fortsetzungszeilen zusammenführen (Zeilen ohne Rufzeichen-Start an vorherigen Datensatz anhängen)
5. Eingebettete Datensätze in Einzelzeilen aufteilen (zweites Rufzeichen-Muster innerhalb einer Zeile)
6. Jede Zeile mit dem Record-Regex parsen
7. Remainder (alles nach `KLASSE,`) per Semikolon in `[name, address?, secondaryLocation?]` aufteilen

### 3.3 DMR-CSV-Format
```
RADIO_ID,CALLSIGN,FIRST_NAME,LAST_NAME,CITY,STATE,COUNTRY
12345678,DL1ABC,Max,Mustermann,Berlin,Berlin,Germany
```
Nur Spalte 1 (RADIO_ID, int) und Spalte 2 (CALLSIGN, String→uppercase) werden verwendet.  
Ergebnis: `Map<String, List<Integer>>` (Rufzeichen → Liste von Radio-IDs, da ein Inhaber mehrere haben kann).

---

## 4. Architektur & Komponenten

### 4.1 Überblick

```
┌─────────────────────────────────────────────────────────────┐
│                        Java Application                     │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ DataManager  │  │ DMRDatabase  │  │ GeocodingService │  │
│  │ (Rufzeichen) │  │ (RadioID)    │  │ (Adress→Koord.)  │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                   │             │
│  ┌──────▼───────────────────────────────────────────────┐   │
│  │              CallsignStatistics                       │   │
│  │  (Lizenzklassen, Bundesländer, Präfixe, Heatmap)     │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    JavaFX UI-Layer                  │    │
│  │  Hauptfenster (Liste + Detail) │ Statistikfenster   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 `CallsignDataManager`

**Verantwortlichkeit:** Laden, Cachen und Bereitstellen aller Rufzeichen-Einträge.

**Zustände (State Machine):**
```
idle → downloading → parsing → done
                             ↘ failed(String)
```

**Datenpipeline:**
1. Beim Start: JSON-Cache laden (`callsigns.json`) → UI sofort befüllen
2. Falls Cache leer ODER älter als 7 Tage: PDF herunterladen
3. PDF parsen (`CallsignPDFParser`)
4. Ergebnis in RAM halten: `List<CallsignEntry>` (sortiert nach Rufzeichen) + `Map<String, CallsignEntry>`
5. JSON-Cache schreiben
6. Letztes Download-Datum persistieren (Preferences-Datei)
7. Statistiken asynchron berechnen
8. Duplikate erkennen (erste Vorkommen behalten, Duplikate loggen)

**Caching:** JSON-Datei im anwendungsspezifischen Datenverzeichnis.
- Windows: `%APPDATA%\RufzeichenSucher\callsigns.json`
- Linux: `~/.local/share/RufzeichenSucher/callsigns.json`
- macOS: `~/Library/Application Support/RufzeichenSucher/callsigns.json`

**Refresh-Logik:**
- `force=false`: Aktualisierung nur wenn kein Cache ODER Cache älter als 7 Tage
- `force=true`: Immer neu herunterladen (Toolbar-Button)
- Netzwerkfehler: Vorhandenen Cache behalten, Fehlerzustand anzeigen

### 4.3 `CallsignPDFParser`

**Verantwortlichkeit:** PDF-Text extrahieren und in `ParsedEntry`-Objekte umwandeln.  
**Bibliothek:** Apache PDFBox 3.x (Apache-2.0-Lizenz).

**Eingabe:** URL/Pfad zur PDF-Datei  
**Ausgabe:** `List<ParsedEntry>` + optionales Stand-Datum

```java
class ParsedEntry {
    String callsign;
    String licenseClass;
    String name;
    String address;          // nullable
    String secondaryLocation; // nullable
}
```

### 4.4 `DMRDatabase`

**Verantwortlichkeit:** DMR-IDs aus CSV laden und O(1)-Lookup bereitstellen.

**Zustände:** `IDLE → LOADING → DONE / FAILED`

**Ladereihenfolge:**
1. JSON-Cache laden (`dmr_ids.json`) → schneller Kaltstart
2. Falls kein Cache: CSV direkt von radioid.net herunterladen (Hintergrund)
3. Falls Cache älter als 30 Tage: Cache sofort laden, dann Hintergrund-Refresh

**In-Memory-Struktur:** `Map<String, List<Integer>>` (Rufzeichen uppercase → Liste Radio-IDs)  
**Performance-Anforderung:** ~306.000 Zeilen parsen in einem Durchlauf, minimale Allokationen, kein Regex – nur String-Split.

### 4.5 `GeocodingService`

**Verantwortlichkeit:** Adressen in geografische Koordinaten (Lat/Lon) auflösen und cachen.  
**Dienst:** Nominatim (OpenStreetMap) – kostenlos, kein API-Key erforderlich, Rate-Limit: 1 req/s.

**Ablauf pro Rufzeichen:**
1. Cache-Lookup (`geocache.json`): Koordinate → sofort zurückgeben
2. Kein Cache, keine Adresse → `noAddress`-Zustand
3. Adresse vorhanden: Geocoding-Anfrage an Nominatim
4. Ergebnis cachen

**Zustände:**
```
idle → geocoding → located(lat, lon)
                 ↘ noAddress
                 ↘ failed(String)
```

**Cache-Format** (`geocache.json`):
```json
{
  "entries": {
    "DL1ABC": [52.5200, 13.4050],
    "DA2XY":  [48.1351, 11.5820]
  }
}
```

### 4.6 `CallsignStatistics`

**Verantwortlichkeit:** Statistische Auswertung der Rufzeichendatenbank.

**Berechnete Kennzahlen:**
1. **Lizenzklassen-Verteilung:** `Map<String, Integer>` (Klasse → Anzahl)
2. **Bundesland-Verteilung:** PLZ aus Adresse extrahieren → via `zipcodes.de.json` Bundesland ermitteln → zählen → mit Bevölkerungsdaten anreichern
3. **Präfix-Verteilung:** Erste 2 Zeichen des Rufzeichens → zählen (DA, DB, DC, DD, DF, DG, DH, DI, DJ, DK, DL, DM, DN, DO, DP, DR)
4. **Versteckte Adressen:** Einträge ohne Adressfeld zählen und Prozentanteil berechnen
5. **Heatmap-Punkte:** Pro PLZ: Koordinate + normiertes Gewicht (sqrt-skaliert, 0..1)
6. **Duplikate:** Liste der entfernten doppelten Rufzeichen

**PLZ-Extraktion aus Adressfeld (Regex):** `\b(\d{5})\b`

**Heatmap-Berechnung:**
- Begrenzungsrahmen Deutschland: lat 47.0–55.5°N, lon 5.5–15.5°E
- Bildgröße: 640×840 Pixel
- Pro PLZ-Punkt: Gauss-ähnlicher Kernel (Radius 22 Pixel), Gewicht = sqrt(Anzahl / Max)
- Pixel-Wert normieren, dann Farbzuweisung: Hue 0.65 (blau) bei niedrig → 0.0 (rot) bei hoch
- Ausgabe: RGBA-Bild via `java.awt.BufferedImage` / `java.awt.Graphics2D`

**Caching:** `statistics.json` (alle berechneten Daten serialisiert)

---

## 5. UI-Anforderungen

### 5.1 Hauptfenster

**Layout:** Zweispaltig (Master-Detail)
- **Linke Spalte (Sidebar/Liste):** Durchsuchbare Liste aller Rufzeichen
- **Rechte Spalte (Detail):** Detailansicht des ausgewählten Rufzeichens

**Sidebar-Liste:**
- Zeigt Rufzeichen, Lizenzklasse-Badge, DMR-Badge, Name, Ort
- **Paginierung:** Beim Scrollen werden weitere 500 Einträge nachgeladen (kein Komplettaufbau aller 70.000 auf einmal)
- **Suchfeld** oben mit drei Modi (Tab):
  - **Rufzeichen:** Uppercase-Substring-Suche, z.B. `"ABC"` findet `"DL1ABC"`
  - **Name:** Locale-sensitiv case-insensitive Suche
  - **Ort / Adresse:** Wortgrenzen-Regex, z.B. `"Berlin"` findet `"12345 Berlin"` aber nicht `"Berliner Str."`
- **Debouncing:** Suche startet frühestens 100ms nach letztem Tastendruck
- **Maximale Suchergebnisse:** 300 (mit `+`-Suffix wenn gekappt)
- Suche läuft im Hintergrund-Thread (kein UI-Blockieren)
- **Anzeige-Titel:** `"Rufzeichen (70123)"` oder `"Ergebnisse (42)"` oder `"Ergebnisse (300+)"`

**Toolbar:**
- Button „Statistiken" → öffnet Statistikfenster
- Button „Aktualisieren" (disabled während Download läuft) → erzwingt Neu-Download
- Text „Stand: 18. Mai 2026" (Stand-Datum aus PDF-Deckblatt)

**Lade-Overlay:** Solange Daten heruntergeladen/geparst werden:
- Halbtransparentes Modal über dem gesamten Fenster
- Zeigt zwei Fortschrittsschritte: BNetzA-Rufzeichenliste + DMR-Datenbank
- Pro Schritt: Statusicon (ausstehend/lädt/fertig/fehler) + Beschreibungstext
- Lade-Spinner (indeterminate Progressbar)
- Overlay bleibt mindestens 1,5 Sekunden sichtbar (damit der Nutzer den Abschluss lesen kann)

**Lizenzklasse-Badges:** Farbige Pillenbeschriftung
- Klasse A → Blau
- Klasse E → Grün
- Klasse N → Orange
- Sonstige → Grau

**DMR-Badge:** Lila Pillenbeschriftung „DMR" (nur wenn mindestens eine DMR-ID vorhanden)

### 5.2 Detail-Ansicht

Wird rechts angezeigt sobald ein Rufzeichen ausgewählt ist. Scrollbare Ansicht mit vier Sektionen:

**Header-Sektion:**
- Großes quadratisches Icon mit Lizenzklassen-Buchstabe (36pt, farbig)
- Rufzeichen (28pt, Monospace, Bold)
- Name (Title3)
- Lizenzklasse + „Amateurfunk" (Subheadline, sekundär)
- Button „Kopieren" → Rufzeichen in Zwischenablage
- Button „QRZ.com" → Browser öffnen (nur wenn auf QRZ.com vorhanden)
- Ladeindikator während QRZ-Check läuft

**QRZ.com-Verfügbarkeits-Check:**
- HTTP-GET auf `https://www.qrz.com/db/{RUFZEICHEN}` mit User-Agent `"Mozilla/5.0"`, Timeout 8s
- HTML-Antwort prüfen: Enthält `"found no results for"` oder `"produced no results"` → nicht vorhanden
- Sonst vorhanden → Button anzeigen
- Fehler → kein Button (still scheitern)

**Adress-Sektion:**
- Titel „Adresse"
- Falls Adresse vorhanden: Hauptanschrift mit Label „HAUPTANSCHRIFT" + Haus-Icon
- Falls keine Adresse: Hinweis auf Widerspruch gem. § 3 Abs. 4 AFuV
- Falls Nebenstandort vorhanden: Zusätzliche Zeile mit Label „NEBENSTANDORT" + Antenne-Icon
- Jede Adresszeile: Button zum Öffnen in OpenStreetMap (`https://www.openstreetmap.org/?q=<Adresse>`)

**DMR-Sektion:**
- Titel „DMR"
- Lädt DMR-DB noch: Lade-Spinner + Text
- Keine DMR-ID: Icon + Text „Keine DMR-ID registriert"
- Eine oder mehrere IDs: Zusammenfassung (z.B. „2 DMR-IDs registriert") + ausklappbare Liste
- Pro ID: Nummer (Monospace), Button „In Zwischenablage kopieren"

**Karten-/Standort-Sektion:**
- Titel „Standort"
- Geocoding läuft: Spinner + Text
- Keine Adresse: Icon + Text
- Geocoding fehlgeschlagen: Warnung + Fehlermeldung
- Koordinate gefunden: Interaktive Karte (320px Höhe) via JavaFX WebView + Leaflet.js, zentriert auf Adresse, mit Marker, Zoom/Kompass-Steuerung

### 5.3 Statistik-Fenster

Eigenständiges Fenster (700×520px Minimum), geöffnet über Toolbar-Button.  
Ladespinner-Overlay während Berechnung läuft.

**Tab 1 – Übersicht (Kacheln):**
- Kacheln in 3-spaltigem Grid
- „Rufzeichen gesamt" + Gesamtzahl
- „Gesperrte Adressen" + Prozent + absolute Zahl
- Pro Lizenzklasse: Anzahl + Prozentanteil
- „Duplikate entfernt" + Anzahl
- „Bundesländer erfasst" + Anzahl

**Tab 2 – Lizenzklassen:**
- Kreisdiagramm (JavaFX PieChart)
- Legende mit Farbe, vollständigem Klassenname, Anzahl, Prozent
- Gesamt-Summe unten

**Tab 3 – Bundesländer:**
- Kreisdiagramm (JavaFX PieChart), eingefärbt nach Bundesland
- Tabellarische Liste: KFZ-Kürzel, Bundesland, Anzahl, Funkamateure je 10.000 Einwohner

**Tab 4 – Präfixe:**
- Balkendiagramm (JavaFX BarChart): X-Achse = Rufzeichen-Präfix (DA, DB, DC...), Y-Achse = Anzahl
- Wertebeschriftung über jedem Balken (kompakte Notation: „12k")

**Tab 5 – Duplikate:**
- Liste aller Rufzeichen, die mehrfach in der PDF vorkamen
- Erklärungstext: „Jeweils erster Eintrag behalten"
- Falls leer: Erfolgsmeldung

### 5.4 Karten-Übersicht

Interaktive Übersichtskarte aller geocodierten Rufzeichen via JavaFX WebView + Leaflet.js + leaflet.markercluster.

**Anforderungen:**
- Karte von Deutschland mit Pins für alle geocodierten Rufzeichen
- Automatisches Clustering bei zu vielen Pins (Kreis mit Anzahl)
- Klick auf einzelnen Pin: Popup mit Rufzeichen, Name, Adresse, Koordinaten
- Klick auf Cluster: Zoom in den Bereich
- Status-Label: „N von M Einträgen geocodiert"

---

## 6. Persistenz & Cache-Verzeichnisse

### 6.1 Cache-Dateien

| Datei | Inhalt | Format |
|---|---|---|
| `callsigns.json` | Alle CallsignEntry-Objekte | JSON-Array |
| `dmr_ids.json` | Rufzeichen → Radio-ID-Listen | JSON-Object (Map) |
| `geocache.json` | Rufzeichen → [lat, lon] | JSON-Object |
| `statistics.json` | Vorberechnete Statistiken | JSON-Object |

### 6.2 Persistierte Einstellungen

| Schlüssel | Typ | Bedeutung |
|---|---|---|
| `CallsignLastDownloadDate` | Timestamp | Letzter Rufzeichen-Download |
| `CallsignPDFStandDate` | Timestamp | Stand-Datum aus PDF-Deckblatt |
| `DMRLastDownloadDate` | Timestamp | Letzter DMR-Download |

### 6.3 Verzeichnis-Strategie
```
Windows:  %APPDATA%\RufzeichenSucher\
Linux:    ~/.local/share/RufzeichenSucher/
macOS:    ~/Library/Application Support/RufzeichenSucher/
```
Implementierung: `AppPaths.java` ermittelt per `System.getProperty("os.name")` / `System.getenv("APPDATA")` das korrekte Verzeichnis und legt es beim ersten Zugriff an.

---

## 7. Performance-Anforderungen

| Operation | Anforderung |
|---|---|
| Kaltstart mit Cache | UI sofort befüllt (< 1s bis erste Einträge sichtbar) |
| Suche in 70.000 Einträgen | Ergebnis < 200ms nach 100ms Debounce |
| PDF-Parsing | Im Hintergrund, kein UI-Blockieren |
| DMR-CSV-Parsing | 306.000 Zeilen ohne Regex, performant |
| Statistikberechnung | Im Hintergrund, Overlay zeigen |
| Listendarstellung | Virtuelle Liste / Paginierung (max. 500 Einträge initial sichtbar) |
| Geocoding | Lazy (nur bei Auswahl), gecacht, kein Main-Thread-Blocking |

---

## 8. Technologie-Stack

| Zweck | Bibliothek / Technologie | Version |
|---|---|---|
| Sprache | Java | 25 |
| UI-Framework | JavaFX | 25.x |
| PDF-Textextraktion | Apache PDFBox | 3.x |
| JSON | Jackson (`jackson-databind`) | 2.x |
| HTTP-Client | `java.net.http.HttpClient` | JDK built-in |
| Charts | JavaFX Charts (built-in) | – |
| Karte (Detail + Übersicht) | JavaFX WebView + Leaflet.js | – |
| Geocoding | Nominatim (OpenStreetMap) | REST API |
| Heatmap-Rendering | `java.awt.BufferedImage` / `Graphics2D` | JDK built-in |
| Build | Maven | 3.9+ |
| Packaging | `jpackage` | JDK built-in |
| Logging | SLF4J + Logback | 2.x |
| Tests | JUnit 5 | 5.x |

---

## 9. Verzeichnisstruktur der AdditionalData-Dateien

Alle Dateien unter `AdditionalData/` sind im Projekt gebündelt:

```
AdditionalData/
├── state_population.json     → Bevölkerungsdaten pro Bundesland
└── zipcodes.de.json          → PLZ → Bundesland + Koordinaten
```

Zugriff in Java: `getClass().getResourceAsStream("/AdditionalData/zipcodes.de.json")`

> Die Rufzeichenliste (PDF) und die DMR-Datenbank (CSV) werden ausschließlich
> aus dem Netz bezogen und lokal gecacht. Es werden keine Fallback-Kopien mitgeliefert.

---

*Dokumentation erstellt: Juni 2026*
