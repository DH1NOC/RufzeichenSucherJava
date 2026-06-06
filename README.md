# 📡 Rufzeichen-Sucher

> Plattformunabhängige Desktop-Applikation zur Suche und Anzeige von deutschen Amateurfunk-Rufzeichen aus der offiziellen Liste der Bundesnetzagentur (BNetzA).

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![JavaFX](https://img.shields.io/badge/JavaFX-25-blue)
![License](https://img.shields.io/badge/License-MIT-green)
![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey)

---

> **Transparenzhinweis: KI-unterstützte Entwicklung**
>
> Diese Anwendung wurde zu einem erheblichen Teil mit Unterstützung von KI-Assistenten entwickelt –
> konkret mit [Claude Code](https://claude.ai/code) von Anthropic. Das betrifft Architektur,
> Implementierung und Dokumentation. Alle Entscheidungen wurden vom Entwickler (Carsten Nohl, DH1NOC)
> begleitet, geprüft und abgenommen. Dieses Projekt steht für einen offenen und transparenten Umgang
> mit KI-gestützter Software-Entwicklung im Amateurfunk-Umfeld.

---

## Inhalt

- [Funktionen](#funktionen)
- [Screenshots](#screenshots)
- [Download & Installation](#download--installation)
- [Aus dem Quellcode bauen](#aus-dem-quellcode-bauen)
- [Architektur](#architektur)
- [Datenquellen](#datenquellen)
- [Cache-Verzeichnisse](#cache-verzeichnisse)
- [Datenschutz](#datenschutz)
- [Mitwirken](#mitwirken)
- [Lizenz](#lizenz)

---

## Funktionen

- **Offline-Suche** in ~70.000 deutschen Amateurfunk-Rufzeichen (Quelle: BNetzA) nach Rufzeichen, Name oder Ort/PLZ
- **Detailansicht** mit Lizenzklasse, Name, Adresse und Nebenstandort
- **DMR-ID-Lookup** via RadioID.net-Datenbank (~306.000 Einträge)
- **Geografische Verortung** per interaktiver Karte (OpenStreetMap / Nominatim)
- **Statistiken**: Lizenzklassen, Bundesländer, Präfixe, Heatmap
- **Automatische Aktualisierung** der Daten (BNetzA: alle 7 Tage, DMR: alle 30 Tage)
- **QRZ.com-Verfügbarkeitscheck** direkt aus der Detailansicht
- **Lokales Caching** für schnellen Kaltstart ohne Internetverbindung

---

## Screenshots

> *Folgen nach dem ersten Release.*

---

## Download & Installation

Das jeweils aktuelle Release mit nativen Installern für alle Plattformen findet sich auf der [Releases-Seite](../../releases):

| Plattform | Datei | Anforderung |
|---|---|---|
| Windows | `.msi` | Windows 10 oder neuer |
| macOS | `.dmg` | macOS 12 oder neuer (Apple Silicon) |
| Linux | `.deb` | Debian / Ubuntu (GTK3) |

Die Installer bringen eine vollständige Java-Laufzeitumgebung mit – **Java muss nicht separat installiert werden**.

---

## Aus dem Quellcode bauen

### Voraussetzungen

| Komponente | Version |
|---|---|
| Java (JDK) | 25 |
| Maven | 3.9+ |

### Starten (Entwicklungsmodus)

```bash
git clone https://github.com/DH1NOC/RufzeichenSucherJava.git
cd RufzeichenSucherJava
mvn javafx:run
```

### Nativen Installer bauen

```bash
# macOS – DMG (Apple Silicon, auf macOS ausführen)
mvn -Ppackage-mac verify -DskipTests

# Windows – MSI (auf Windows ausführen, WiX Toolset 3.x erforderlich)
mvn -Ppackage-win verify -DskipTests

# Linux – DEB (auf Linux ausführen, fakeroot erforderlich)
mvn -Ppackage-linux verify -DskipTests
```

Ausgabe liegt jeweils in `target/dist/`.

---

## Architektur

```
┌─────────────────────────────────────────────────────────────┐
│                        Java Application                     │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ DataManager  │  │ DMRDatabase  │  │ GeocodingService │  │
│  │ (Rufzeichen) │  │ (RadioID)    │  │ (Nominatim OSM)  │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                   │             │
│  ┌──────▼──────────────────────────────────────────────┐    │
│  │              CallsignStatistics                      │    │
│  │  (Lizenzklassen, Bundesländer, Präfixe, Heatmap)    │    │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    JavaFX UI-Layer                  │    │
│  │  Hauptfenster (Liste + Detail) │ Statistikfenster   │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Technologie-Stack:**

| Zweck | Bibliothek |
|---|---|
| UI-Framework | JavaFX 25 |
| PDF-Textextraktion | Apache PDFBox 3.x |
| JSON | Jackson |
| HTTP-Client | `java.net.http.HttpClient` (JDK built-in) |
| Karte | JavaFX WebView + Leaflet.js |
| Charts | JavaFX Charts |
| Build | Maven |
| Packaging | `jpackage` (JDK built-in) |

Die vollständige Architekturdokumentation befindet sich in [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Datenquellen

| Quelle | Beschreibung | Lizenz / Nutzung |
|---|---|---|
| [Bundesnetzagentur](https://data.bundesnetzagentur.de/) | Offizielle Rufzeichenliste (PDF) | Open Data |
| [RadioID.net](https://radioid.net/) | DMR-Datenbank (CSV) | Frei nutzbar |
| [OpenStreetMap / Nominatim](https://nominatim.openstreetmap.org/) | Geocoding | ODbL, max. 1 req/s |
| `zipcodes.de.json` | PLZ → Bundesland + Koordinaten | Gebündelt |
| `state_population.json` | Bevölkerungsdaten je Bundesland | Gebündelt |

---

## Cache-Verzeichnisse

Die Anwendung speichert Caches und Einstellungen plattformkonform:

| Betriebssystem | Pfad |
|---|---|
| Windows | `%APPDATA%\RufzeichenSucher\` |
| Linux | `~/.local/share/RufzeichenSucher/` |
| macOS | `~/Library/Application Support/RufzeichenSucher/` |

---

## Datenschutz

- Die Anwendung überträgt **keine personenbezogenen Daten** an Dritte.
- Beim Geocoding wird die Adresse des jeweiligen Rufzeichen-Inhabers an Nominatim (OpenStreetMap) übermittelt. Dies geschieht nur auf explizite Nutzerinteraktion (Auswahl eines Eintrags) und ist durch die [Nominatim Usage Policy](https://operations.osmfoundation.org/policies/nominatim/) gedeckt.
- Der QRZ.com-Verfügbarkeitscheck überträgt lediglich das gesuchte Rufzeichen.
- Alle anderen Datenbankabfragen erfolgen vollständig lokal.

---

## Mitwirken

Pull Requests und Issues sind willkommen!

1. Fork erstellen
2. Feature-Branch anlegen: `git checkout -b feature/mein-feature`
3. Änderungen committen: `git commit -m 'feat: mein Feature'`
4. Branch pushen: `git push origin feature/mein-feature`
5. Pull Request öffnen

Bitte halte dich an die Konventionen in [`AGENTS.md`](AGENTS.md).

---

## Lizenz

MIT License – siehe [`LICENSE`](LICENSE) für Details.

---

*73 de DH1NOC*
