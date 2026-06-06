package de.rufzeichensucher.data;

import de.rufzeichensucher.model.CallsignEntry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CallsignPDFParser {

    private static final Logger log = LoggerFactory.getLogger(CallsignPDFParser.class);

    /** Matches a callsign record start: DX9ABC..., LicenseClass, ... */
    private static final Pattern RECORD_START = Pattern.compile(
            "^(D[A-PR-Z][0-9][A-Z0-9]{1,9}),\\s*([A-Z]{1,2})\\s*,\\s*(.+)$");

    /** Detects a second embedded callsign within a line. */
    private static final Pattern EMBEDDED_CALLSIGN = Pattern.compile(
            "(D[A-PR-Z][0-9][A-Z0-9]{1,9}),\\s*([A-Z]{1,2})\\s*,\\s*(.+)$");

    /** Lines to discard (headers, page numbers, single standalone numbers). */
    private static final Pattern NOISE_LINE = Pattern.compile(
            "^\\s*(?:bundesnetzagentur|rufzeichenliste|stand\\s*:|seite\\s+\\d+|\\d{1,4})\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** "Stand: vom 18. Mai 2026" – extracts the date portion. */
    private static final Pattern STAND_DATE = Pattern.compile(
            "vom\\s+(\\d{1,2}\\.\\s*\\w+\\s+\\d{4})", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter GERMAN_DATE =
            DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN);

    public record ParseResult(List<CallsignEntry> entries, @Nullable LocalDate standDate) {}

    private CallsignPDFParser() {}

    public static ParseResult parse(Path pdfPath) throws IOException {
        try (var doc = Loader.loadPDF(pdfPath.toFile())) {
            return doParse(doc);
        }
    }

    public static ParseResult parse(InputStream stream) throws IOException {
        try (var doc = Loader.loadPDF(stream.readAllBytes())) {
            return doParse(doc);
        }
    }

    private static ParseResult doParse(PDDocument doc) throws IOException {
        var stripper = new PDFTextStripper();
        var raw = stripper.getText(doc);

        var standDate = extractStandDate(raw);
        var lines = normalizeAndFilter(raw);
        var merged = mergeAndSplit(lines);
        var entries = parseRecords(merged);

        log.info("Parsed {} entries, stand date: {}", entries.size(), standDate);
        return new ParseResult(entries, standDate);
    }

    @Nullable
    private static LocalDate extractStandDate(String text) {
        var m = STAND_DATE.matcher(text);
        if (!m.find()) return null;
        var raw = m.group(1).replaceAll("\\s+", " ").trim();
        try {
            return LocalDate.parse(raw, GERMAN_DATE);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse stand date: '{}'", raw);
            return null;
        }
    }

    /** Normalize Unicode artefacts and return non-noise lines. */
    private static List<String> normalizeAndFilter(String text) {
        var lines = new ArrayList<String>();
        for (var line : text.split("\n")) {
            // Remove soft hyphens and replace non-breaking spaces
            var normalized = line
                    .replace("­", "")
                    .replace(" ", " ")
                    .stripTrailing();
            if (normalized.isBlank()) continue;
            if (NOISE_LINE.matcher(normalized).matches()) continue;
            lines.add(normalized);
        }
        return lines;
    }

    /**
     * Merges continuation lines into their predecessor and splits lines that
     * contain two embedded records.
     */
    private static List<String> mergeAndSplit(List<String> lines) {
        var result = new ArrayList<String>();

        for (var line : lines) {
            if (RECORD_START.matcher(line).matches()) {
                result.add(line);
            } else if (!result.isEmpty() && !RECORD_START.matcher(line).matches()) {
                // Continuation line: append to last record
                result.set(result.size() - 1, result.getLast() + " " + line.strip());
            } else {
                result.add(line);
            }
        }

        // Second pass: split lines that contain two embedded callsigns
        var split = new ArrayList<String>(result.size() + 16);
        for (var line : result) {
            var m = EMBEDDED_CALLSIGN.matcher(line);
            // Find first match
            if (!m.find()) {
                split.add(line);
                continue;
            }
            int firstStart = m.start();
            // Look for a second match starting after the first callsign
            var m2 = EMBEDDED_CALLSIGN.matcher(line);
            m2.find(); // advance to first
            if (m2.find(firstStart + 3)) {
                split.add(line.substring(0, m2.start()).stripTrailing());
                split.add(line.substring(m2.start()));
            } else {
                split.add(line);
            }
        }

        return split;
    }

    private static List<CallsignEntry> parseRecords(List<String> lines) {
        var entries = new ArrayList<CallsignEntry>(lines.size());

        for (var line : lines) {
            var m = RECORD_START.matcher(line);
            if (!m.matches()) continue;

            var callsign = m.group(1).toUpperCase(Locale.ROOT);
            var licenseClass = m.group(2);
            var remainder = m.group(3).trim();

            // remainder is split by ';': name [; address [; secondaryLocation]]
            var parts = remainder.split(";", -1);

            var name = parts[0].trim();
            @Nullable String address = parts.length > 1 ? parts[1].trim() : null;
            // address may be empty string if field was present but blank
            if (address != null && address.isEmpty()) address = null;

            @Nullable String secondary = null;
            if (parts.length > 2) {
                secondary = String.join(";", java.util.Arrays.copyOfRange(parts, 2, parts.length)).trim();
                if (secondary.isEmpty()) secondary = null;
            }

            entries.add(new CallsignEntry(callsign, licenseClass, name, address, secondary));
        }

        return entries;
    }
}
