package de.rufzeichensucher;

import de.rufzeichensucher.data.DMRDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DMRDatabaseTest {

    private DMRDatabase db;

    @BeforeEach
    void setUp() {
        db = new DMRDatabase();
    }

    /**
     * Invokes the private parseCsv method via reflection so we can test it
     * without touching the network or the filesystem.
     */
    @SuppressWarnings("unchecked")
    private List<Integer> parseAndLookup(String csv, String callsign) throws Exception {
        var method = DMRDatabase.class.getDeclaredMethod("parseCsv", InputStream.class);
        method.setAccessible(true);
        var stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        var map = (java.util.Map<String, List<Integer>>) method.invoke(null, stream);

        // Re-inject map into db via reflection
        var field = DMRDatabase.class.getDeclaredField("index");
        field.setAccessible(true);
        field.set(db, map);

        return db.getDmrIds(callsign);
    }

    @Test
    void loadsCallsignFromCsv() throws Exception {
        var csv = "RADIO_ID,CALLSIGN\n1234567,DL1ABC\n";
        var ids = parseAndLookup(csv, "DL1ABC");
        assertEquals(List.of(1234567), ids);
    }

    @Test
    void returnsMultipleIdsForSameCallsign() throws Exception {
        var csv = "RADIO_ID,CALLSIGN\n1111111,DL1ABC\n2222222,DL1ABC\n";
        var ids = parseAndLookup(csv, "DL1ABC");
        assertEquals(2, ids.size());
        assertTrue(ids.contains(1111111));
        assertTrue(ids.contains(2222222));
    }

    @Test
    void handlesEmptyOrMalformedLines() throws Exception {
        var csv = "RADIO_ID,CALLSIGN\n\nnotanumber,DL1ABC\n1234567,DL2XY\n";
        var ids = parseAndLookup(csv, "DL2XY");
        assertEquals(List.of(1234567), ids);

        var malformed = parseAndLookup(csv, "DL1ABC");
        assertTrue(malformed.isEmpty());
    }

    @Test
    void lookupIsUppercaseInsensitive() throws Exception {
        var csv = "RADIO_ID,CALLSIGN\n9876543,DL1ABC\n";
        var idsLower = parseAndLookup(csv, "dl1abc");
        assertEquals(List.of(9876543), idsLower);
    }

    @Test
    void returnsEmptyListForUnknownCallsign() {
        assertEquals(List.of(), db.getDmrIds("DX0UNKNOWN"));
    }
}
