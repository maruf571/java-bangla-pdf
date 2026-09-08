package com.example.banglapdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code ToUnicode} CMap: the table that tells a reader what text a
 * glyph id stands for.
 *
 * <p>Without it an Identity-H PDF is unsearchable and uncopyable, because the
 * codes in the content stream are glyph indices private to one font, not
 * characters. With it, selecting a line of Bangla and pressing copy yields the
 * Unicode that was typed, in logical order -- not the visual order the glyphs
 * happen to be drawn in.
 */
final class ToUnicodeCMap {

    /** Adobe's limit on entries per bfchar block. */
    private static final int ENTRIES_PER_BLOCK = 100;

    private ToUnicodeCMap() {
    }

    static byte[] build(GlyphUsage usage) {
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(usage.textByGlyph().entrySet());
        StringBuilder cmap = new StringBuilder(entries.size() * 24 + 512);
        cmap.append("""
                /CIDInit /ProcSet findresource begin
                12 dict begin
                begincmap
                /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                /CMapName /Adobe-Identity-UCS def
                /CMapType 2 def
                1 begincodespacerange
                <0000> <FFFF>
                endcodespacerange
                """);

        for (int start = 0; start < entries.size(); start += ENTRIES_PER_BLOCK) {
            int end = Math.min(start + ENTRIES_PER_BLOCK, entries.size());
            cmap.append(end - start).append(" beginbfchar\n");
            for (int i = start; i < end; i++) {
                Map.Entry<Integer, String> entry = entries.get(i);
                cmap.append('<').append(PdfSyntax.glyphCode(entry.getKey())).append("> ")
                        .append(PdfSyntax.unicodeCodes(entry.getValue())).append('\n');
            }
            cmap.append("endbfchar\n");
        }

        cmap.append("""
                endcmap
                CMapName currentdict /CMap defineresource pop
                end
                end
                """);
        return PdfSyntax.ascii(cmap.toString());
    }
}
