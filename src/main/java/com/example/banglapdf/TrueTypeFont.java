package com.example.banglapdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.fontbox.ttf.TTFParser;

/**
 * The two font facts that have to agree between the shaper and the content
 * stream: how many units make an em, and how wide each glyph is once that em
 * is rescaled to PDF's fixed 1000-unit glyph space.
 *
 * <p>Reading a TrueType font is otherwise not this project's job any more.
 * {@link BanglaPdfService} hands the same font bytes to {@link PdfDocumentBuilder},
 * which embeds the font -- and computes its own {@code /W} array -- through
 * Apache PDFBox. This class exists only so that {@link TextShaper} (scaling
 * HarfBuzz's output) and {@link ContentStreamBuilder} (computing {@code TJ}
 * kerning numbers) derive their numbers from the exact same table PDFBox will
 * use, via the same FontBox parser, so nothing between the three can drift by
 * a rounding step.
 *
 * <p>Outlines are never touched: PDFBox embeds the font file whole, so the
 * viewer draws exactly what HarfBuzz's glyph ids point at.
 */
final class TrueTypeFont {

    private final org.apache.fontbox.ttf.TrueTypeFont font;
    final int unitsPerEm;

    TrueTypeFont(byte[] bytes) {
        requireTrueTypeOutlines(bytes);
        try {
            this.font = new TTFParser().parse(new ByteArrayInputStream(bytes));
            this.unitsPerEm = font.getHeader().getUnitsPerEm();
            if (unitsPerEm == 0) {
                throw new BanglaPdfException("Font declares unitsPerEm = 0");
            }
        } catch (IOException e) {
            throw new BanglaPdfException("Not a usable TrueType font", e);
        }
    }

    /** Advance width of a glyph in PDF glyph space (1000 units per em), matching PDFBox's own {@code /W}. */
    int widthInThousandths(int glyphId) {
        try {
            return Math.round(font.getHorizontalMetrics().getAdvanceWidth(glyphId) * 1000f / unitsPerEm);
        } catch (IOException e) {
            throw new BanglaPdfException("Could not read glyph metrics for glyph " + glyphId, e);
        }
    }

    /**
     * Rejects CFF-flavoured OpenType ({@code OTTO}) up front, with a specific
     * explanation, rather than letting PDFBox fail deep inside font embedding.
     * {@code PDType0Font} needs {@code FontFile3}/Type1C for those; this
     * project only wires up the {@code FontFile2} (TrueType-outline) path.
     */
    private static void requireTrueTypeOutlines(byte[] bytes) {
        if (bytes.length < 4) {
            throw new BanglaPdfException("Font file is truncated");
        }
        long tag = ((long) (bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        if (tag == 0x4F54544FL) { // 'OTTO'
            throw new BanglaPdfException(
                    "This is a CFF-flavoured OpenType font. Embedding it needs FontFile3/Type1C; "
                            + "use a TrueType-outline (.ttf) font such as Noto Sans Bengali instead.");
        }
    }
}
