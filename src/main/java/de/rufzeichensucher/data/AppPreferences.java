package de.rufzeichensucher.data;

import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.prefs.Preferences;

public final class AppPreferences {

    public static final String KEY_CALLSIGN_LAST_DOWNLOAD = "CallsignLastDownloadDate";
    public static final String KEY_CALLSIGN_PDF_STAND     = "CallsignPDFStandDate";
    public static final String KEY_DMR_LAST_DOWNLOAD      = "DMRLastDownloadDate";

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(AppPreferences.class);

    private AppPreferences() {}

    public static Optional<LocalDate> getDate(String key) {
        @Nullable var value = PREFS.get(key, null);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static void setDate(String key, LocalDate date) {
        PREFS.put(key, date.toString());
    }

    /**
     * Returns {@code true} if the stored date is absent or older than {@code days} days.
     * Used to decide whether a refresh is needed.
     */
    public static boolean isOlderThan(String key, int days) {
        return getDate(key)
                .map(date -> date.isBefore(LocalDate.now().minusDays(days)))
                .orElse(true);
    }
}
