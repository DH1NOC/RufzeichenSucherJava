package de.rufzeichensucher.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public final class DMRDatabase {

    private static final Logger log = LoggerFactory.getLogger(DMRDatabase.class);
    private static final String CSV_URL = "https://radioid.net/static/user.csv";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum State { IDLE, LOADING, DONE, FAILED }

    private final ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(State.IDLE);

    /** callsign (uppercase) → list of DMR IDs */
    private volatile Map<String, List<Integer>> index = Map.of();

    public DMRDatabase() {}

    public ReadOnlyObjectProperty<State> stateProperty() { return stateProperty; }
    public State getState() { return stateProperty.get(); }

    /**
     * Initialises the database from cache if available; otherwise downloads fresh
     * from radioid.net in the background. Must be called from the FX thread.
     */
    public void init(ExecutorService executor) {
        var cachePath = AppPaths.dmrIdsJson();
        if (Files.exists(cachePath) && !AppPreferences.isOlderThan(AppPreferences.KEY_DMR_LAST_DOWNLOAD, 30)) {
            loadFromCache(cachePath);
            stateProperty.set(State.DONE);
        } else if (Files.exists(cachePath)) {
            loadFromCache(cachePath);
            stateProperty.set(State.DONE);
            executor.submit(this::downloadAndCache);
        } else {
            stateProperty.set(State.LOADING);
            executor.submit(this::downloadAndCache);
        }
    }

    /** Returns DMR IDs for the given callsign (case-insensitive). Empty list if none found. */
    public List<Integer> getDmrIds(String callsign) {
        var ids = index.get(callsign.toUpperCase(Locale.ROOT));
        return ids != null ? Collections.unmodifiableList(ids) : List.of();
    }

    // -------------------------------------------------------------------------

    private void loadFromCache(Path cachePath) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, List<Integer>> loaded = MAPPER.readValue(
                    cachePath.toFile(), new TypeReference<Map<String, List<Integer>>>() {});
            index = loaded;
            log.info("DMR database loaded from cache: {} callsigns", index.size());
        } catch (IOException e) {
            log.warn("Could not load DMR cache: {}", e.getMessage());
        }
    }

    private void downloadAndCache() {
        log.info("Downloading DMR database from {}", CSV_URL);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(CSV_URL))
                    .header("User-Agent", "RufzeichenSucher/1.0")
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("DMR download returned HTTP {}", response.statusCode());
                setStateFailed();
                return;
            }
            try (var body = response.body()) {
                var newIndex = parseCsv(body);
                index = newIndex;
                persistCache(newIndex);
                AppPreferences.setDate(AppPreferences.KEY_DMR_LAST_DOWNLOAD, java.time.LocalDate.now());
                log.info("DMR database updated: {} callsigns", newIndex.size());
                Platform.runLater(() -> stateProperty.set(State.DONE));
            }
        } catch (IOException | InterruptedException e) {
            log.warn("DMR download failed: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            setStateFailed();
        }
    }

    private void setStateFailed() {
        Platform.runLater(() -> {
            if (stateProperty.get() == State.LOADING) stateProperty.set(State.FAILED);
        });
    }

    private static Map<String, List<Integer>> parseCsv(java.io.InputStream stream) throws IOException {
        var map = new HashMap<String, List<Integer>>(400_000);
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var parts = line.split(",", -1);
                if (parts.length < 2) continue;
                var radioIdStr = parts[0].trim();
                var callsign = parts[1].trim().toUpperCase(Locale.ROOT);
                if (callsign.isEmpty() || radioIdStr.isEmpty()) continue;
                try {
                    var id = Integer.parseInt(radioIdStr);
                    map.computeIfAbsent(callsign, k -> new ArrayList<>(1)).add(id);
                } catch (NumberFormatException ignored) {}
            }
        }
        return map;
    }

    private void persistCache(Map<String, List<Integer>> data) {
        try {
            MAPPER.writeValue(AppPaths.dmrIdsJson().toFile(), data);
        } catch (IOException e) {
            log.warn("Could not persist DMR cache", e);
        }
    }
}
