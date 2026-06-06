package de.rufzeichensucher.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.rufzeichensucher.model.CallsignEntry;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CallsignDataManager {

    private static final Logger log = LoggerFactory.getLogger(CallsignDataManager.class);

    private static final String PDF_URL =
            "https://data.bundesnetzagentur.de/Bundesnetzagentur/SharedDocs/Downloads/DE/"
            + "Sachgebiete/Telekommunikation/Unternehmen_Institutionen/Frequenzen/Amateurfunk/"
            + "Rufzeichenliste/rufzeichenliste_afu.pdf";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .findAndRegisterModules();

    public enum State { IDLE, DOWNLOADING, PARSING, DONE, FAILED }

    // -- Observable state (JavaFX thread) -----------------------------------------
    private final ObjectProperty<State> stateProperty = new SimpleObjectProperty<>(State.IDLE);

    // -- Data (written from background, read from any thread) ---------------------
    private volatile List<CallsignEntry> entries = List.of();
    private volatile Map<String, CallsignEntry> index = Map.of();
    private volatile List<CallsignEntry> duplicates = List.of();
    private volatile LocalDate standDate = null;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "callsign-data-worker");
        t.setDaemon(true);
        return t;
    });

    public CallsignDataManager() {}

    // ---- Public API -------------------------------------------------------------

    public ReadOnlyObjectProperty<State> stateProperty() { return stateProperty; }
    public State getState() { return stateProperty.get(); }

    public List<CallsignEntry> getEntries() { return entries; }
    public List<CallsignEntry> getDuplicates() { return duplicates; }
    public LocalDate getStandDate() { return standDate; }

    /**
     * Loads cached data immediately (if available) and triggers a background
     * refresh if the cache is absent or stale (> 7 days).
     */
    public void init() {
        var cachePath = AppPaths.callsignsJson();
        if (Files.exists(cachePath)) {
            loadFromCache(cachePath);
        }
        boolean needsRefresh = !Files.exists(cachePath)
                || AppPreferences.isOlderThan(AppPreferences.KEY_CALLSIGN_LAST_DOWNLOAD, 7);
        if (needsRefresh) {
            executor.submit(() -> refreshAsync(false));
        }
    }

    /** Forces a full re-download and re-parse regardless of cache age. */
    public void forceRefresh() {
        executor.submit(() -> refreshAsync(true));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ---- Internal ---------------------------------------------------------------

    private void loadFromCache(java.nio.file.Path cachePath) {
        try {
            var root = MAPPER.readTree(cachePath.toFile());
            List<CallsignEntry> loaded;
            List<CallsignEntry> dups;
            if (root.isArray()) {
                // Legacy format: plain array without duplicates
                loaded = MAPPER.convertValue(root, new TypeReference<>() {});
                dups   = List.of();
            } else {
                // Current format: { "entries": [...], "duplicates": [...] }
                loaded = MAPPER.convertValue(root.get("entries"),    new TypeReference<>() {});
                var dupsNode = root.get("duplicates");
                dups = dupsNode != null
                        ? MAPPER.convertValue(dupsNode, new TypeReference<>() {})
                        : List.of();
            }
            var savedStand = AppPreferences.getDate(AppPreferences.KEY_CALLSIGN_PDF_STAND).orElse(null);
            applyEntries(loaded, savedStand);
            this.duplicates = Collections.unmodifiableList(new ArrayList<>(dups));
            setState(State.DONE);
            log.info("Cache loaded: {} entries, {} duplicates", loaded.size(), dups.size());
        } catch (IOException e) {
            log.warn("Could not load callsign cache: {}", e.getMessage());
        }
    }

    private void refreshAsync(boolean force) {
        if (!force) {
            var currentState = stateProperty.get();
            if (currentState == State.DOWNLOADING || currentState == State.PARSING) return;
        }
        try {
            setState(State.DOWNLOADING);

            byte[] pdfBytes = downloadPdf();

            setState(State.PARSING);

            var result = CallsignPDFParser.parse(new java.io.ByteArrayInputStream(pdfBytes));
            var deduped = dedup(result.entries());

            applyEntries(deduped.unique(), result.standDate());
            this.duplicates = Collections.unmodifiableList(deduped.duplicates());

            persistCache(deduped.unique(), deduped.duplicates());
            AppPreferences.setDate(AppPreferences.KEY_CALLSIGN_LAST_DOWNLOAD, LocalDate.now());
            if (result.standDate() != null) {
                AppPreferences.setDate(AppPreferences.KEY_CALLSIGN_PDF_STAND, result.standDate());
            }

            setState(State.DONE);
            log.info("Refresh complete: {} entries, {} duplicates",
                    deduped.unique().size(), deduped.duplicates().size());
        } catch (Exception e) {
            log.error("Refresh failed", e);
            setState(State.FAILED);
        }
    }

    private byte[] downloadPdf() throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(PDF_URL))
                    .header("User-Agent", "RufzeichenSucher/1.0")
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("PDF download returned HTTP " + response.statusCode());
            }
            return response.body();
        }
    }

    private record DedupResult(List<CallsignEntry> unique, List<CallsignEntry> duplicates) {}

    private static DedupResult dedup(List<CallsignEntry> entries) {
        var seen   = new LinkedHashSet<String>(entries.size());
        var dups   = new ArrayList<CallsignEntry>();
        var unique = new ArrayList<CallsignEntry>(entries.size());
        for (var e : entries) {
            if (seen.add(e.getCallsign())) unique.add(e);
            else dups.add(e);
        }
        return new DedupResult(unique, dups);
    }

    private void applyEntries(List<CallsignEntry> list, LocalDate date) {
        var sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        var idx = new HashMap<String, CallsignEntry>(sorted.size() * 2);
        for (var e : sorted) idx.put(e.getCallsign(), e);
        this.entries = Collections.unmodifiableList(sorted);
        this.index = Collections.unmodifiableMap(idx);
        if (date != null) this.standDate = date;
    }

    private void persistCache(List<CallsignEntry> data, List<CallsignEntry> dups) {
        try {
            var wrapper = MAPPER.createObjectNode();
            wrapper.set("entries",    MAPPER.valueToTree(data));
            wrapper.set("duplicates", MAPPER.valueToTree(dups));
            MAPPER.writeValue(AppPaths.callsignsJson().toFile(), wrapper);
        } catch (IOException e) {
            log.warn("Could not persist callsign cache", e);
        }
    }

    private void setState(State s) {
        Platform.runLater(() -> stateProperty.set(s));
    }
}
