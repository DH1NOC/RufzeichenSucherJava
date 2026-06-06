package de.rufzeichensucher.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.rufzeichensucher.model.CallsignEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NOMINATIM_URL =
            "https://nominatim.openstreetmap.org/search?q=%s&format=json&limit=1&countrycodes=de";

    // ---- GeoState sealed interface ------------------------------------------

    public sealed interface GeoState
            permits GeoState.Idle, GeoState.Loading, GeoState.Located,
                    GeoState.NoAddress, GeoState.Failed {
        record Idle()                            implements GeoState {}
        record Loading()                         implements GeoState {}
        record Located(double lat, double lon)   implements GeoState {}
        record NoAddress()                       implements GeoState {}
        record Failed(String reason)             implements GeoState {}
    }

    // ---- State --------------------------------------------------------------

    private final ConcurrentHashMap<String, double[]> cache = new ConcurrentHashMap<>();
    private final Semaphore rateLimiter = new Semaphore(1);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "geo-rate-limiter");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "geo-worker");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ---- Public API ---------------------------------------------------------

    /** Loads the geocache from disk (fast, synchronous). */
    public void init() {
        loadCache();
    }

    public void shutdown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    /**
     * Returns a future that resolves to a {@link GeoState}.
     * Completes immediately with {@link GeoState.NoAddress} when no address is available,
     * or with {@link GeoState.Located} from cache. Otherwise geocodes asynchronously.
     * Never throws – always returns a completed state.
     */
    public CompletableFuture<GeoState> geocode(CallsignEntry entry) {
        if (entry.getAddress() == null) {
            return CompletableFuture.completedFuture(new GeoState.NoAddress());
        }
        var cached = cache.get(entry.getCallsign());
        if (cached != null) {
            return CompletableFuture.completedFuture(new GeoState.Located(cached[0], cached[1]));
        }
        return CompletableFuture.supplyAsync(() -> doGeocode(entry), executor);
    }

    // ---- Internal -----------------------------------------------------------

    private GeoState doGeocode(CallsignEntry entry) {
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GeoState.Failed("Unterbrochen");
        }
        try {
            var address = entry.getAddress();
            if (address == null) return new GeoState.NoAddress();
            var encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
            var uri = URI.create(NOMINATIM_URL.formatted(encoded));
            var request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "RufzeichenSucher/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var arr = MAPPER.readTree(response.body());
            if (!arr.isArray() || arr.isEmpty()) {
                return new GeoState.Failed("Koordinaten nicht gefunden");
            }
            var first = arr.get(0);
            var latNode = first.get("lat");
            var lonNode = first.get("lon");
            if (latNode == null || lonNode == null) {
                return new GeoState.Failed("Koordinaten-Felder fehlen in Nominatim-Antwort");
            }
            var lat = latNode.asDouble();
            var lon = lonNode.asDouble();
            cache.put(entry.getCallsign(), new double[]{lat, lon});
            persistCache();
            return new GeoState.Located(lat, lon);
        } catch (Exception e) {
            log.debug("Geocoding failed for {}: {}", entry.getCallsign(), e.getMessage());
            return new GeoState.Failed(e.getMessage());
        } finally {
            // Release rate-limit permit after 1 second to enforce max 1 req/s
            scheduler.schedule(() -> rateLimiter.release(), 1, TimeUnit.SECONDS);
        }
    }

    private void loadCache() {
        var path = AppPaths.geocacheJson();
        if (!Files.exists(path)) return;
        try {
            var root = MAPPER.readTree(path.toFile());
            var entries = root.get("entries");
            if (entries == null || !entries.isObject()) return;
            entries.fields().forEachRemaining(e -> {
                var arr = e.getValue();
                if (arr.isArray() && arr.size() == 2) {
                    cache.put(e.getKey(), new double[]{arr.get(0).asDouble(), arr.get(1).asDouble()});
                }
            });
            log.info("Geocache loaded: {} entries", cache.size());
        } catch (Exception e) {
            log.warn("Could not load geocache: {}", e.getMessage());
        }
    }

    private void persistCache() {
        try {
            var root = MAPPER.createObjectNode();
            var entries = MAPPER.createObjectNode();
            cache.forEach((callsign, coords) ->
                    entries.putArray(callsign).add(coords[0]).add(coords[1]));
            root.set("entries", entries);
            MAPPER.writeValue(AppPaths.geocacheJson().toFile(), root);
        } catch (Exception e) {
            log.warn("Could not persist geocache: {}", e.getMessage());
        }
    }
}
