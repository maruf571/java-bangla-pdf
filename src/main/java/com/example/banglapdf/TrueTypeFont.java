package com.example.banglapdf;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A read-only view of the handful of TrueType/OpenType tables a PDF needs in
 * order to embed a font and lay out its glyphs.
 *
 * <p>Deliberately shallow: the glyph outlines are never parsed or rewritten.
 * The font file is embedded byte-for-byte as {@code FontFile2}, so the viewer
 * draws exactly the outlines the shaper chose. All this class extracts is
 * bookkeeping -- the em size, per-glyph advance widths, and the metrics the
 * {@code FontDescriptor} dictionary is required to carry.
 */
final class TrueTypeFont {

    private static final String[] REQUIRED_TABLES = {"head", "maxp", "hhea", "hmtx"};

    final byte[] bytes;
    final String postScriptName;
    final int unitsPerEm;
    final int numGlyphs;
    /** Advance width per glyph id, in font units. */
    final int[] advanceWidths;
    final int ascender;
    final int descender;
    final int xMin;
    final int yMin;
    final int xMax;
    final int yMax;
    final double italicAngle;
    /** Glyph id to the character it represents on its own, from the font's cmap. */
    private final Map<Integer, Integer> unicodeByGlyph;

    TrueTypeFont(byte[] bytes) {
        this.bytes = bytes;
        Map<String, int[]> tables = readTableDirectory(bytes);
        for (String tag : REQUIRED_TABLES) {
            if (!tables.containsKey(tag)) {
                throw new BanglaPdfException("Not a usable TrueType font: the '" + tag + "' table is missing");
            }
        }

        int head = tables.get("head")[0];
        this.unitsPerEm = u16(bytes, head + 18);
        if (unitsPerEm == 0) {
            throw new BanglaPdfException("Font declares unitsPerEm = 0");
        }
        this.xMin = s16(bytes, head + 36);
        this.yMin = s16(bytes, head + 38);
        this.xMax = s16(bytes, head + 40);
        this.yMax = s16(bytes, head + 42);

        this.numGlyphs = u16(bytes, tables.get("maxp")[0] + 4);

        int hhea = tables.get("hhea")[0];
        this.ascender = s16(bytes, hhea + 4);
        this.descender = s16(bytes, hhea + 6);
        int numHMetrics = u16(bytes, hhea + 34);

        // hmtx stores full metrics for the first numHMetrics glyphs; every glyph
        // after that reuses the last advance width (a monospaced tail).
        int hmtx = tables.get("hmtx")[0];
        this.advanceWidths = new int[numGlyphs];
        int lastWidth = 0;
        for (int gid = 0; gid < numGlyphs; gid++) {
            if (gid < numHMetrics) {
                lastWidth = u16(bytes, hmtx + gid * 4);
            }
            advanceWidths[gid] = lastWidth;
        }

        int[] post = tables.get("post");
        this.italicAngle = post == null ? 0 : fixed32(bytes, post[0] + 4);
        this.postScriptName = readPostScriptName(bytes, tables.get("name"));
        this.unicodeByGlyph = readReverseCmap(bytes, tables.get("cmap"), numGlyphs);
    }

    /**
     * The character a glyph stands for when it appears on its own, or null.
     *
     * <p>This is the font's own character map, read backwards. It is the
     * trustworthy half of the {@code ToUnicode} table: every glyph that exists
     * because some character was typed gets its own unambiguous answer here,
     * rather than inheriting the text of whichever cluster happened to use it
     * first. Glyphs that only exist as the output of a substitution -- Bangla
     * conjuncts such as ক্ষ, which no single character maps to -- are absent,
     * and the shaper's cluster text answers for them instead.
     */
    String unicodeForGlyph(int glyphId) {
        Integer codePoint = unicodeByGlyph.get(glyphId);
        return codePoint == null ? null : new String(Character.toChars(codePoint));
    }

    /** Advance width of a glyph, in PDF glyph space (1000 units per em). */
    int widthInThousandths(int glyphId) {
        return toThousandths(advanceWidths[glyphId]);
    }

    /** Converts font units to PDF glyph space, which is always 1000 units per em. */
    int toThousandths(int fontUnits) {
        return Math.round(fontUnits * 1000f / unitsPerEm);
    }

    /**
     * Inverts the font's cmap into glyph id to code point. Formats 4 (the
     * 16-bit Unicode map every font has) and 12 (the full 21-bit map) cover
     * every font in practical use.
     */
    private static Map<Integer, Integer> readReverseCmap(byte[] b, int[] cmapTable, int numGlyphs) {
        Map<Integer, Integer> reverse = new HashMap<>();
        if (cmapTable == null) {
            return reverse;
        }
        int base = cmapTable[0];
        int numSubtables = u16(b, base + 2);
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < numSubtables; i++) {
            int record = base + 4 + i * 8;
            int platformId = u16(b, record);
            int encodingId = u16(b, record + 2);
            int subtable = base + (int) u32(b, record + 4);
            // Prefer the widest Unicode map available.
            int score = switch (platformId * 100 + encodingId) {
                case 310 -> 4;  // Windows, UCS-4
                case 301 -> 3;  // Windows, BMP
                case 4, 6 -> 2; // Unicode 2.0+
                case 3 -> 1;    // Unicode BMP
                default -> platformId == 0 ? 1 : 0;
            };
            if (score > bestScore) {
                bestScore = score;
                best = subtable;
            }
        }
        if (best < 0 || best + 4 > b.length) {
            return reverse;
        }

        int format = u16(b, best);
        if (format == 4) {
            int segCount = u16(b, best + 6) / 2;
            int endCodes = best + 14;
            int startCodes = endCodes + segCount * 2 + 2;
            int idDeltas = startCodes + segCount * 2;
            int idRangeOffsets = idDeltas + segCount * 2;
            for (int seg = 0; seg < segCount; seg++) {
                int end = u16(b, endCodes + seg * 2);
                int start = u16(b, startCodes + seg * 2);
                int delta = u16(b, idDeltas + seg * 2);
                int rangeOffset = u16(b, idRangeOffsets + seg * 2);
                for (int code = start; code <= end && code != 0xFFFF; code++) {
                    int glyph;
                    if (rangeOffset == 0) {
                        glyph = (code + delta) & 0xFFFF;
                    } else {
                        int index = idRangeOffsets + seg * 2 + rangeOffset + (code - start) * 2;
                        if (index + 1 >= b.length) {
                            continue;
                        }
                        glyph = u16(b, index);
                        if (glyph != 0) {
                            glyph = (glyph + delta) & 0xFFFF;
                        }
                    }
                    remember(reverse, glyph, code, numGlyphs);
                }
            }
        } else if (format == 12) {
            int groups = (int) u32(b, best + 12);
            for (int g = 0; g < groups; g++) {
                int group = best + 16 + g * 12;
                if (group + 12 > b.length) {
                    break;
                }
                long start = u32(b, group);
                long end = u32(b, group + 4);
                long startGlyph = u32(b, group + 8);
                for (long code = start; code <= end && code - start < 0x10000; code++) {
                    remember(reverse, (int) (startGlyph + code - start), (int) code, numGlyphs);
                }
            }
        }
        return reverse;
    }

    /** Keeps the lowest code point that reaches a glyph, so the mapping is stable. */
    private static void remember(Map<Integer, Integer> reverse, int glyphId, int codePoint, int numGlyphs) {
        if (glyphId <= 0 || glyphId >= numGlyphs) {
            return;
        }
        reverse.merge(glyphId, codePoint, Math::min);
    }

    private static Map<String, int[]> readTableDirectory(byte[] b) {
        if (b.length < 12) {
            throw new BanglaPdfException("Font file is truncated");
        }
        long version = u32(b, 0);
        if (version != 0x00010000L && version != 0x4F54544FL /* 'OTTO' */ && version != 0x74727565L /* 'true' */) {
            throw new BanglaPdfException("Unrecognised font format; expected a TrueType or OpenType file");
        }
        if (version == 0x4F54544FL) {
            throw new BanglaPdfException(
                    "This is a CFF-flavoured OpenType font. Embedding it needs FontFile3/Type1C; "
                            + "use a TrueType-outline (.ttf) font such as Noto Sans Bengali instead.");
        }
        int numTables = u16(b, 4);
        Map<String, int[]> tables = new HashMap<>(numTables * 2);
        int record = 12;
        for (int i = 0; i < numTables && record + 16 <= b.length; i++, record += 16) {
            String tag = new String(b, record, 4, StandardCharsets.US_ASCII);
            tables.put(tag, new int[]{(int) u32(b, record + 8), (int) u32(b, record + 12)});
        }
        return tables;
    }

    /**
     * Reads name ID 6 (the PostScript name) from the 'name' table, preferring
     * the Windows/Unicode record. Falls back to a safe placeholder, since PDF
     * only uses this string to label the font.
     */
    private static String readPostScriptName(byte[] b, int[] nameTable) {
        String found = null;
        if (nameTable != null) {
            int base = nameTable[0];
            int count = u16(b, base + 2);
            int stringOffset = base + u16(b, base + 4);
            for (int i = 0; i < count; i++) {
                int record = base + 6 + i * 12;
                if (u16(b, record + 6) != 6) { // nameID 6 == PostScript name
                    continue;
                }
                int platformId = u16(b, record);
                int length = u16(b, record + 8);
                int offset = stringOffset + u16(b, record + 10);
                if (offset + length > b.length) {
                    continue;
                }
                // Platform 3 (Windows) stores UTF-16BE; platform 1 (Mac) stores single bytes.
                String value = new String(b, offset, length,
                        platformId == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1);
                if (platformId == 3) {
                    return sanitise(value);
                }
                if (found == null) {
                    found = sanitise(value);
                }
            }
        }
        return found != null && !found.isEmpty() ? found : "EmbeddedFont";
    }

    /** Keeps only characters that are legal, unescaped, in a PDF name object. */
    private static String sanitise(String name) {
        StringBuilder clean = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c > ' ' && c < 0x7F && "()<>[]{}/%#".indexOf(c) < 0) {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int s16(byte[] b, int off) {
        return (short) u16(b, off);
    }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** A 16.16 fixed-point number, the format 'post' uses for italicAngle. */
    private static double fixed32(byte[] b, int off) {
        return ((int) u32(b, off)) / 65536.0;
    }
}
