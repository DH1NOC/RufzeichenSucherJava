package de.rufzeichensucher.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class AppPaths {

    private static final Path DATA_DIR = resolveDataDirectory();

    private AppPaths() {}

    private static Path resolveDataDirectory() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path dir;
        if (os.contains("win")) {
            var appData = System.getenv("APPDATA");
            dir = Path.of(appData != null ? appData : System.getProperty("user.home"),
                    "RufzeichenSucher");
        } else if (os.contains("mac")) {
            dir = Path.of(System.getProperty("user.home"),
                    "Library", "Application Support", "RufzeichenSucher");
        } else {
            var xdgDataHome = System.getenv("XDG_DATA_HOME");
            dir = xdgDataHome != null
                    ? Path.of(xdgDataHome, "RufzeichenSucher")
                    : Path.of(System.getProperty("user.home"), ".local", "share", "RufzeichenSucher");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory: " + dir, e);
        }
        return dir;
    }

    public static Path dataDirectory() { return DATA_DIR; }

    public static Path callsignsJson()  { return DATA_DIR.resolve("callsigns.json"); }

    public static Path dmrIdsJson() { return DATA_DIR.resolve("dmr_ids.json"); }

    public static Path geocacheJson() { return DATA_DIR.resolve("geocache.json"); }

    public static Path statisticsJson() { return DATA_DIR.resolve("statistics.json"); }
}
