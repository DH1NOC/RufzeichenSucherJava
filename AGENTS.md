# AGENTS.md – Leitfaden für KI-Agenten & Copilot

Dieses Dokument beschreibt Konventionen, Architektur und Regeln für KI-Assistenten (GitHub Copilot, Claude, ChatGPT etc.), die an diesem Projekt mitarbeiten.

---

## Projektkontext

**Rufzeichen-Sucher** ist eine JavaFX-Desktop-Applikation zur Suche in deutschen Amateurfunk-Rufzeichen.  
Die vollständige Architekturdokumentation liegt in [`ARCHITECTURE.md`](ARCHITECTURE.md).  

**Beide Dateien sind die verbindliche Grundlage für alle Implementierungsentscheidungen.**  
Vor dem Schreiben von Code müssen diese Dokumente gelesen werden.

---

## Technologie-Stack (verbindlich)

| Zweck | Bibliothek / Technologie | Version |
|---|---|---|
| Sprache | Java | 25 |
| UI-Framework | JavaFX | 25.x |
| PDF-Parsing | Apache PDFBox | 3.x |
| JSON | Jackson (`jackson-databind`) | 2.x |
| HTTP | `java.net.http.HttpClient` | JDK built-in |
| Charts | JavaFX Charts (built-in) | – |
| Karte (Detail) | JavaFX WebView + Leaflet.js | – |
| Karte (Übersicht) | JavaFX WebView + Leaflet.js + leaflet.markercluster | – |
| Geocoding | Nominatim (OpenStreetMap) | REST API |
| Build | Maven | 3.9+ |
| Packaging | `jpackage` | JDK built-in (Java 14+) |
| Logging | SLF4J + Logback | 2.x |
| Tests | JUnit 5 | 5.x |

**Keine abweichenden Technologien** ohne explizite Absprache im Issue.

---

## Paketstruktur

```
de.rufzeichensucher
├── App.java                        ← JavaFX-Einstiegspunkt
├── model/
│   └── CallsignEntry.java          ← Kerndatenmodell (POJO + Jackson)
├── data/
│   ├── AppPaths.java               ← OS-spezifische Cache-Verzeichnisse
│   ├── AppPreferences.java         ← java.util.prefs.Preferences-Wrapper
│   ├── CallsignDataManager.java    ← Datenpipeline + State Machine
│   ├── CallsignPDFParser.java      ← PDFBox-basierter Parser
│   ├── DMRDatabase.java            ← CSV-Loader + In-Memory-Map
│   ├── GeocodingService.java       ← Nominatim + Cache + Rate-Limiting
│   └── CallsignStatistics.java     ← Statistische Auswertungen
└── ui/
    ├── MainWindow.java             ← Stage-Setup
    ├── MainWindowController.java
    ├── DetailPaneController.java
    ├── StatisticsWindowController.java
    └── MapOverviewController.java
```

FXML-Dateien liegen unter `src/main/resources/de/rufzeichensucher/`.  
Ressourcen (PDF, CSV, JSON) liegen unter `src/main/resources/AdditionalData/`.

---

## Coding-Konventionen

### Allgemein
- **Sprache der Code-Kommentare und Variablennamen:** Englisch
- **Sprache der UI-Texte:** Deutsch
- **Einrückung:** 4 Spaces (kein Tab)
- **Zeilenlänge:** max. 120 Zeichen
- **Encoding:** UTF-8 überall

### Java-spezifisch
- Java 25 Features sind erlaubt: Records, Sealed Classes, Switch Expressions, Text Blocks, `var`, Pattern Matching
- `null`-Felder mit `@Nullable` / `@NonNull` (IntelliJ-Annotationen) markieren
- Keine Raw Types, keine unchecked Casts ohne `@SuppressWarnings("unchecked")` mit Kommentar
- Alle `AutoCloseable`-Ressourcen mit try-with-resources

### Threading-Regeln (KRITISCH)
- **GUI-Updates ausschließlich** auf dem JavaFX Application Thread: `Platform.runLater(() -> ...)`
- **Hintergrundarbeiten** in `ExecutorService` (kein `new Thread()` direkt)
- `CompletableFuture` für verkettete asynchrone Operationen
- Keine `Thread.sleep()` im Application Thread

### Fehlerbehandlung
- Netzwerkfehler: Graceful Degradation – bestehenden Cache behalten, Fehlerzustand in UI anzeigen
- PDF-Parsing-Fehler: Logging auf ERROR-Level, leere Liste zurückgeben (kein App-Crash)
- Geocoding-Fehler: `GeoState.Failed(reason)` zurückgeben, kein Exception-Propagieren

---

## Datenmodell-Regeln

### `CallsignEntry`
- `address` ist **nullable** (Widerspruch gem. § 3 Abs. 4 AFuV) – niemals `""` als Ersatz
- `city` wird **abgeleitet** aus `address` (Regex: `\d{5}\s+(.+)`) – kein separates Setzen
- `callsign` ist immer **uppercase**
- Jackson: `@JsonIgnoreProperties(ignoreUnknown = true)` für Cache-Rückwärtskompatibilität

### PDF-Parsing (Wichtigste Sonderfälle)
1. Zwei Records in einer Zeile (zweites Rufzeichen-Muster innerhalb der Zeile aufteilen)
2. Adresse über mehrere Rohzeilen (Fortsetzungszeilen zusammenführen)
3. Noise-Zeilen herausfiltern (Regex: `^\s*(?:bundesnetzagentur|rufzeichenliste|stand\s*:|seite\s+\d+|\d{1,4})\s*$`)
4. Unicode normalisieren: `\u00AD` → leer, `\u00A0` → Leerzeichen

Rufzeichen-Regex: `^D[A-PR-Z][0-9][A-Z0-9]{1,9},\s*[A-Z]{1,2}\s*,`

---

## State Machines

### `CallsignDataManager`
```
IDLE → DOWNLOADING → PARSING → DONE
                             ↘ FAILED(message)
```
Zustand als `ObjectProperty<State>` – UI bindet direkt daran.

### `GeocodingService` (pro Rufzeichen)
```
IDLE → LOADING → LOCATED(lat, lon)
               ↘ NO_ADDRESS
               ↘ FAILED(reason)
```

---

## Caching-Strategie

| Cache-Datei | Invalidierung |
|---|---|
| `callsigns.json` | Älter als 7 Tage ODER `force=true` |
| `dmr_ids.json` | Älter als 30 Tage |
| `geocache.json` | Nie automatisch (manuelles Löschen) |
| `statistics.json` | Wenn `callsigns.json` neu geschrieben wird |

---

## Performance-Anforderungen

| Operation | Ziel |
|---|---|
| Kaltstart mit Cache | < 1s bis erste Listeneinträge sichtbar |
| Suche in 70.000 Einträgen | < 200ms (nach 100ms Debounce) |
| DMR-CSV-Parsing (306k Zeilen) | Kein Regex, nur `split(",", -1)` |
| ListView | Virtuell / Paginiert (max. 500 Einträge initial) |
| Geocoding | Lazy (nur bei Auswahl), max. 1 req/s |

---

## Was NICHT geändert werden darf (ohne Issue)

- Der Geocoding-Dienst (Nominatim) darf **nicht** gegen einen kostenpflichtigen Dienst ausgetauscht werden
- Die Karten-Implementierung bleibt **WebView + Leaflet.js** (kein proprietäres SDK)
- Die Cache-Verzeichnisse entsprechen den OS-Konventionen (s. `AppPaths.java`) – keine Hardcoded-Pfade
- Ressourcen-Dateien (`AdditionalData/`) werden über `getResourceAsStream()` geladen, nie über `File`-Pfade

---

## Commit-Konventionen

Format: `<type>(<scope>): <kurze Beschreibung>` (Conventional Commits)

| Type | Bedeutung |
|---|---|
| `feat` | Neue Funktionalität |
| `fix` | Fehlerbehebung |
| `refactor` | Umstrukturierung ohne Funktionsänderung |
| `test` | Tests hinzufügen/ändern |
| `docs` | Dokumentation |
| `chore` | Build, Abhängigkeiten, Konfiguration |
| `perf` | Performance-Verbesserung |

Beispiele:
```
feat(parser): add inline-record splitting for merged PDF lines
fix(geocoding): handle HTTP 429 rate-limit response
test(dmr): add CSV parsing test for malformed lines
```

---

## Häufige Fehlerquellen (Achtung!)

1. **PDFBox-Thread-Safety:** `PDDocument` ist **nicht thread-safe** – pro Parse-Vorgang neue Instanz
2. **JavaFX-Thread:** Jede Property-Änderung, die die UI berührt, muss über `Platform.runLater()` laufen
3. **Nominatim User-Agent:** Pflichtfeld! Ohne `User-Agent`-Header werden Requests blockiert
4. **Soft-Hyphens im PDF-Text:** `\u00AD` muss vor dem Regex-Matching entfernt werden
5. **`address`-Null vs. leer:** Immer auf `null` prüfen, nie auf `isEmpty()` allein

---

*Letzte Aktualisierung: Juni 2026*

