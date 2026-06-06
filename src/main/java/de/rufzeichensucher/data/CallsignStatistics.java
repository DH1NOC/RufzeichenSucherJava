package de.rufzeichensucher.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.rufzeichensucher.model.CallsignEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Objects;

public final class CallsignStatistics {

    private static final Logger log = LoggerFactory.getLogger(CallsignStatistics.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ZIP_PATTERN = Pattern.compile("(\\d{5})");

    // ---- Public result types ------------------------------------------------

    public record StateStats(String name, long count, double perTenThousand) {}

    public record HeatPoint(double lat, double lon, double weight) {}

    public record Result(
            int total,
            int blockedAddresses,
            Map<String, Long> byLicenseClass,
            List<StateStats> byState,
            Map<String, Long> byPrefix,
            List<CallsignEntry> duplicates,
            List<HeatPoint> heatPoints
    ) {}

    // ---- Internal lookup types ----------------------------------------------

    private record ZipEntry(String state, double lat, double lon) {}

    // ---- Loaded reference data ----------------------------------------------

    private final Map<String, ZipEntry> zipMap;
    private final Map<String, Long>     statePopulation;

    public CallsignStatistics() {
        zipMap          = loadZipcodes();
        statePopulation = loadStatePopulation();
        log.debug("CallsignStatistics loaded {} ZIP codes, {} states", zipMap.size(), statePopulation.size());
    }

    // ---- Computation --------------------------------------------------------

    public Result compute(List<CallsignEntry> entries, List<CallsignEntry> duplicates) {
        int total   = entries.size();
        int blocked = (int) entries.stream().filter(e -> e.getAddress() == null).count();

        if (statePopulation.isEmpty()) {
            log.warn("Bevölkerungsdaten fehlen – Pro-Kopf-Statistik nicht aussagekräftig");
        }

        // License class distribution
        Map<String, Long> byClass = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> Objects.requireNonNullElse(e.getLicenseClass(), "?"),
                        Collectors.counting()));

        // State distribution + per-capita
        Map<String, Long> rawByState = new HashMap<>();
        for (CallsignEntry e : entries) {
            String zip = extractZip(e.getAddress());
            if (zip != null) {
                ZipEntry ze = zipMap.get(zip);
                if (ze != null) rawByState.merge(ze.state(), 1L, Long::sum);
            }
        }

        List<StateStats> byState = rawByState.entrySet().stream()
                .map(en -> {
                    long pop = statePopulation.getOrDefault(en.getKey(), 1L);
                    double per = (en.getValue() * 10_000.0) / pop;
                    return new StateStats(en.getKey(), en.getValue(), per);
                })
                .sorted(Comparator.comparingLong(StateStats::count).reversed())
                .toList();

        // Prefix distribution (first 2 chars), all variants
        Map<String, Long> byPrefix = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getCallsign().substring(0, Math.min(2, e.getCallsign().length())),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        // Heatmap: aggregate by ZIP, normalize
        Map<String, Long> zipCounts = new HashMap<>();
        for (CallsignEntry e : entries) {
            String zip = extractZip(e.getAddress());
            if (zip != null) zipCounts.merge(zip, 1L, Long::sum);
        }
        long maxCount = zipCounts.values().stream().mapToLong(v -> v).max().orElse(1);
        List<HeatPoint> heatPoints = zipCounts.entrySet().stream()
                .filter(en -> zipMap.containsKey(en.getKey()))
                .map(en -> {
                    ZipEntry ze = zipMap.get(en.getKey());
                    double weight = Math.sqrt((double) en.getValue() / maxCount);
                    return new HeatPoint(ze.lat(), ze.lon(), weight);
                })
                .toList();

        return new Result(total, blocked, byClass, byState, byPrefix, duplicates, heatPoints);
    }

    // ---- Helpers ------------------------------------------------------------

    private static String extractZip(String address) {
        if (address == null) return null;
        var m = ZIP_PATTERN.matcher(address);
        return m.find() ? m.group(1) : null;
    }

    // ---- Resource loading ---------------------------------------------------

    private static Map<String, ZipEntry> loadZipcodes() {
        try (var is = CallsignStatistics.class.getResourceAsStream("/AdditionalData/zipcodes.de.json")) {
            if (is == null) { log.warn("zipcodes.de.json not found in classpath"); return Map.of(); }
            List<Map<String, Object>> rows = MAPPER.readValue(is, new TypeReference<>() {});
            var result = new HashMap<String, ZipEntry>(rows.size() * 2);
            for (var row : rows) {
                if (!(row.get("zipcode")   instanceof String zip))    continue;
                if (!(row.get("state")     instanceof String state))   continue;
                if (!(row.get("latitude")  instanceof String latStr))  continue;
                if (!(row.get("longitude") instanceof String lonStr))  continue;
                try {
                    result.putIfAbsent(zip, new ZipEntry(state,
                            Double.parseDouble(latStr), Double.parseDouble(lonStr)));
                } catch (NumberFormatException ignored) {}
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            log.error("Failed to load zipcodes", e);
            return Map.of();
        }
    }

    private static Map<String, Long> loadStatePopulation() {
        try (var is = CallsignStatistics.class.getResourceAsStream("/AdditionalData/state_population.json")) {
            if (is == null) { log.warn("state_population.json not found in classpath"); return Map.of(); }
            List<Map<String, Object>> rows = MAPPER.readValue(is, new TypeReference<>() {});
            var result = new HashMap<String, Long>();
            for (var row : rows) {
                if (!(row.get("state")      instanceof String state)) continue;
                if (!(row.get("population") instanceof Number n))     continue;
                result.put(state, n.longValue());
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException e) {
            log.error("Failed to load state population", e);
            return Map.of();
        }
    }
}
