package com.example.banglapdf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Small helpers for writing the primitive value types PDF content-stream
 * operators use. PDFBox owns the surrounding file structure (see
 * {@link PdfDocumentBuilder}); this is only for the bytes
 * {@link ContentStreamBuilder} hands it through {@code appendRawCommands}.
 */
final class PdfSyntax {

    private PdfSyntax() {
    }

    /**
     * Formats a real number the way PDF wants it: no exponent, no trailing
     * noise. PDF has no notion of scientific notation, so {@code Float.toString}
     * is not safe here.
     */
    static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-4) {
            return Long.toString(Math.round(value));
        }
        String s = String.format(Locale.ROOT, "%.3f", value);
        // Trim trailing zeros; "1.500" and "1.5" are the same number but not the
        // same number of bytes, and a content stream is mostly numbers.
        s = s.indexOf('.') < 0 ? s : s.replaceAll("0++$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    /** A glyph id as the four hex digits of a two-byte Identity-H code. */
    static String glyphCode(int glyphId) {
        return String.format("%04X", glyphId & 0xFFFF);
    }

    /**
     * A PDF text string in hexadecimal UTF-16BE, with the byte-order mark that
     * tells a reader it is Unicode rather than PDFDocEncoding. Bangla cannot be
     * expressed any other way in a PDF string, which is what an
     * {@code ActualText} value is.
     */
    static String unicodeString(String text) {
        StringBuilder hex = new StringBuilder(text.length() * 4 + 6);
        hex.append("<FEFF");
        for (byte b : text.getBytes(StandardCharsets.UTF_16BE)) {
            hex.append(String.format("%02X", b));
        }
        return hex.append('>').toString();
    }
}
