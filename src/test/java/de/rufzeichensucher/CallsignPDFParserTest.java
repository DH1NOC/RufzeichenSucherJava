package de.rufzeichensucher;

import de.rufzeichensucher.data.CallsignPDFParser;
import de.rufzeichensucher.model.CallsignEntry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CallsignPDFParserTest {

    /** Creates a minimal in-memory PDF with the given text on one page. */
    private static ByteArrayInputStream pdfWith(String... lines) throws IOException {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                cs.beginText();
                cs.setFont(font, 10);
                cs.newLineAtOffset(50, 750);
                for (var line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -14);
                }
                cs.endText();
            }
            doc.save(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    void parsesSingleRecord() throws IOException {
        var result = CallsignPDFParser.parse(pdfWith("DA6VA, A, Klaus Mueller"));
        assertEquals(1, result.entries().size());
        var e = result.entries().getFirst();
        assertEquals("DA6VA", e.getCallsign());
        assertEquals("A", e.getLicenseClass());
        assertEquals("Klaus Mueller", e.getName());
        assertNull(e.getAddress());
        assertNull(e.getSecondaryLocation());
    }

    @Test
    void parsesEntryWithAddress() throws IOException {
        // Street and PLZ+city are on separate PDF lines; the parser merges them into one address segment
        var result = CallsignPDFParser.parse(pdfWith(
                "DH9XY, E, Maria Schmidt; Bergweg 5",
                "54321 Bergheim"));
        assertEquals(1, result.entries().size());
        var e = result.entries().getFirst();
        assertEquals("DH9XY", e.getCallsign());
        assertNotNull(e.getAddress());
        assertTrue(e.getAddress().contains("54321"), "Address should contain PLZ");
        assertEquals("Bergheim", e.getCity());
    }

    @Test
    void parsesEntryWithSecondaryLocation() throws IOException {
        // Address spans two PDF lines; secondary location is in the second line after a semicolon
        var result = CallsignPDFParser.parse(pdfWith(
                "DH9XY, E, Maria Schmidt; Bergweg 5",
                "54321 Bergheim; Antennenstandort"));
        assertEquals(1, result.entries().size());
        var e = result.entries().getFirst();
        assertEquals("Antennenstandort", e.getSecondaryLocation());
        assertNotNull(e.getAddress());
        assertEquals("Bergheim", e.getCity());
    }

    @Test
    void parsesEntryWithoutAddress() throws IOException {
        var result = CallsignPDFParser.parse(pdfWith("DA6VA, A, Klaus Mueller"));
        var e = result.entries().getFirst();
        assertNull(e.getAddress());
    }

    @Test
    void filtersNoiseLines() throws IOException {
        var result = CallsignPDFParser.parse(pdfWith(
                "Bundesnetzagentur",
                "Rufzeichenliste",
                "Stand:",
                "Seite 42",
                "123",
                "DA6VA, A, Klaus Mueller"
        ));
        assertEquals(1, result.entries().size());
    }

    @Test
    void normalizesUnicodeArtefacts() throws IOException {
        // soft-hyphen ­ in callsign and non-breaking space
        var line = "DA6­VA, A, Klaus Mueller";
        var result = CallsignPDFParser.parse(pdfWith(line));
        assertFalse(result.entries().isEmpty(), "Should parse at least one entry despite unicode artefacts");
    }

    @Test
    void callsignIsUppercased() throws IOException {
        var result = CallsignPDFParser.parse(pdfWith("DA6VA, A, Klaus Mueller"));
        assertEquals("DA6VA", result.entries().getFirst().getCallsign());
    }
}
