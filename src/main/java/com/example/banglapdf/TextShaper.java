package com.example.banglapdf;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Turns Unicode text into positioned glyphs using HarfBuzz.
 *
 * <p>This is where "perfect Bangla" is actually decided. Bangla is not a
 * one-character-one-glyph script: consonants fuse into conjuncts, the vowel
 * signs -ি and -ে are typed after their consonant but drawn before it, and the
 * reph (র্) is typed first but drawn last, as a hook over the following
 * cluster. Resolving all of that is the job of the font's OpenType GSUB/GPOS
 * tables, and HarfBuzz is the reference implementation of that resolution.
 *
 * <p>Text is split into script-homogeneous runs first, because HarfBuzz shapes
 * one script at a time; the full string is still handed to it as context so
 * that shaping decisions at a run boundary see their neighbours.
 *
 * <p>Instances hold native memory and must be {@link #close() closed}. They are
 * not thread-safe.
 */
final class TextShaper implements AutoCloseable {

    /**
     * One glyph the font should draw, in font units (that is, in
     * {@code unitsPerEm}ths of an em).
     *
     * @param glyphId  index into the font's own glyph table, after substitution
     * @param cluster  index into the source {@code String} this glyph came from
     * @param xAdvance how far the pen moves after drawing
     * @param yAdvance vertical pen movement (zero for horizontal scripts)
     * @param xOffset  horizontal nudge applied to this glyph only, from GPOS
     * @param yOffset  vertical nudge applied to this glyph only, from GPOS
     */
    record ShapedGlyph(int glyphId, int cluster, int xAdvance, int yAdvance, int xOffset, int yOffset) {
    }

    /**
     * A group of glyphs that together render one indivisible run of source
     * characters -- a Bangla conjunct such as ক্ষ, or a consonant plus its
     * reordered vowel sign. Copy-and-paste has to work at this granularity,
     * not per glyph.
     *
     * @param firstGlyph  index of the first glyph of the group
     * @param glyphCount  number of glyphs in the group
     * @param sourceStart first source character the group stands for, inclusive
     * @param sourceEnd   last source character the group stands for, exclusive
     */
    record Cluster(int firstGlyph, int glyphCount, int sourceStart, int sourceEnd) {

        boolean spansSeveralCharacters() {
            return sourceEnd - sourceStart > 1;
        }
    }

    /** A shaped run of text, ready to be drawn or measured. */
    record ShapedText(String source, List<ShapedGlyph> glyphs, List<Cluster> clusters, int unitsPerEm) {

        /** Total advance in points, when set at {@code fontSize}. */
        float width(float fontSize) {
            int fontUnits = 0;
            for (ShapedGlyph glyph : glyphs) {
                fontUnits += glyph.xAdvance();
            }
            return fontUnits * fontSize / unitsPerEm;
        }

        boolean isEmpty() {
            return glyphs.isEmpty();
        }

        /** The source characters a cluster stands for. */
        String textOf(Cluster cluster) {
            return source.substring(cluster.sourceStart(), cluster.sourceEnd());
        }

        /** True if the font had no glyph for some character and fell back to .notdef. */
        boolean hasMissingGlyphs() {
            return glyphs.stream().anyMatch(glyph -> glyph.glyphId() == 0);
        }
    }

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment blob;
    private final MemorySegment face;
    private final MemorySegment font;
    private final MemorySegment language;
    private final int unitsPerEm;

    TextShaper(byte[] fontBytes, int unitsPerEm, String bcp47Language) {
        this.unitsPerEm = unitsPerEm;
        MemorySegment fontData = arena.allocate(fontBytes.length);
        MemorySegment.copy(fontBytes, 0, fontData, JAVA_BYTE, 0, fontBytes.length);

        this.blob = HarfBuzzLibrary.blobCreate(fontData, fontBytes.length, HarfBuzzLibrary.MEMORY_MODE_READONLY);
        this.face = HarfBuzzLibrary.faceCreate(blob, 0);
        this.font = HarfBuzzLibrary.fontCreate(face);
        HarfBuzzLibrary.otFontSetFuncs(font);
        // Scale == unitsPerEm makes HarfBuzz report advances in raw font units,
        // which is what the PDF width tables are derived from too, so the two
        // agree exactly and no rounding drift accumulates along a line.
        HarfBuzzLibrary.fontSetScale(font, unitsPerEm, unitsPerEm);
        this.language = HarfBuzzLibrary.languageFromString(arena.allocateFrom(bcp47Language));
    }

    /** Shapes one line of text. The text must not contain line breaks. */
    ShapedText shape(String text) {
        if (text.isEmpty()) {
            return new ShapedText(text, List.of(), List.of(), unitsPerEm);
        }
        Utf8 utf8 = Utf8.encode(text);
        List<ShapedGlyph> glyphs = new ArrayList<>(text.length() + 8);

        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment nativeText = callArena.allocate(utf8.bytes.length);
            MemorySegment.copy(utf8.bytes, 0, nativeText, JAVA_BYTE, 0, utf8.bytes.length);
            MemorySegment countOut = callArena.allocate(JAVA_INT);

            for (int[] run : scriptRuns(text)) {
                int startByte = utf8.charToByte[run[0]];
                int endByte = utf8.charToByte[run[1]];
                shapeRun(nativeText, utf8.bytes.length, startByte, endByte - startByte, countOut, utf8, glyphs);
            }
        }
        return new ShapedText(text, List.copyOf(glyphs), groupIntoClusters(text, glyphs), unitsPerEm);
    }

    private void shapeRun(MemorySegment nativeText, int textLength, int itemOffset, int itemLength,
                          MemorySegment countOut, Utf8 utf8, List<ShapedGlyph> out) {
        MemorySegment buffer = HarfBuzzLibrary.bufferCreate();
        try {
            // Passing the whole string with an item range (rather than just the
            // substring) lets HarfBuzz see the characters on either side of the
            // run as shaping context.
            HarfBuzzLibrary.bufferAddUtf8(buffer, nativeText, textLength, itemOffset, itemLength);
            HarfBuzzLibrary.bufferSetLanguage(buffer, language);
            // Script and direction are inferred from the characters themselves,
            // which is more robust than hard-coding "Bengali, left-to-right".
            HarfBuzzLibrary.bufferGuessSegmentProperties(buffer);
            HarfBuzzLibrary.shape(font, buffer);

            MemorySegment infos = HarfBuzzLibrary.glyphInfos(buffer, countOut);
            int count = countOut.get(JAVA_INT, 0);
            MemorySegment positions = HarfBuzzLibrary.glyphPositions(buffer, countOut);
            if (count == 0) {
                return;
            }

            long stride = HarfBuzzLibrary.GLYPH_STRUCT_SIZE;
            MemorySegment infoArray = infos.reinterpret(count * stride);
            MemorySegment positionArray = positions.reinterpret(count * stride);
            for (int i = 0; i < count; i++) {
                long info = i * stride;      // { codepoint, mask, cluster, var1, var2 }
                long position = i * stride;  // { x_advance, y_advance, x_offset, y_offset, var }
                out.add(new ShapedGlyph(
                        infoArray.get(JAVA_INT, info),
                        utf8.byteToChar[infoArray.get(JAVA_INT, info + 8)],
                        positionArray.get(JAVA_INT, position),
                        positionArray.get(JAVA_INT, position + 4),
                        positionArray.get(JAVA_INT, position + 8),
                        positionArray.get(JAVA_INT, position + 12)));
            }
        } finally {
            HarfBuzzLibrary.bufferDestroy(buffer);
        }
    }

    /**
     * Groups glyphs that share a cluster, and records the source text each
     * group stands for. Several characters can collapse into one glyph (ক + ্
     * + ষ becomes the single glyph ক্ষ) and one character can explode into
     * several, so this mapping is genuinely many-to-many and is the only way
     * back to the original text.
     */
    private static List<Cluster> groupIntoClusters(String text, List<ShapedGlyph> glyphs) {
        List<Cluster> clusters = new ArrayList<>();
        int i = 0;
        while (i < glyphs.size()) {
            int cluster = glyphs.get(i).cluster();
            int j = i + 1;
            while (j < glyphs.size() && glyphs.get(j).cluster() == cluster) {
                j++;
            }
            int end = j < glyphs.size() ? glyphs.get(j).cluster() : text.length();
            // Reordering can make the next cluster start earlier in the source
            // than this one; fall back to a single character when that happens.
            if (end <= cluster) {
                end = Math.min(text.length(), cluster + 1);
            }
            clusters.add(new Cluster(i, j - i, Math.min(cluster, text.length()), end));
            i = j;
        }
        return List.copyOf(clusters);
    }

    /**
     * Splits the text into maximal runs of a single script. Punctuation, digits
     * and spaces (script {@code COMMON}) and combining marks
     * ({@code INHERITED}) join the run they follow rather than breaking it.
     *
     * @return {@code [startChar, endChar)} pairs
     */
    private static List<int[]> scriptRuns(String text) {
        List<int[]> runs = new ArrayList<>();
        int runStart = 0;
        Character.UnicodeScript runScript = null;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeScript script = scriptOf(codePoint);
            if (script != null) {
                if (runScript != null && script != runScript) {
                    runs.add(new int[]{runStart, i});
                    runStart = i;
                }
                runScript = script;
            }
            i += Character.charCount(codePoint);
        }
        runs.add(new int[]{runStart, text.length()});
        return runs;
    }

    /** The script of a code point, or null if it should inherit its neighbour's. */
    private static Character.UnicodeScript scriptOf(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return switch (script) {
            case COMMON, INHERITED, UNKNOWN -> null;
            default -> script;
        };
    }

    @Override
    public void close() {
        try {
            HarfBuzzLibrary.fontDestroy(font);
            HarfBuzzLibrary.faceDestroy(face);
            HarfBuzzLibrary.blobDestroy(blob);
        } finally {
            arena.close();
        }
    }

    /**
     * UTF-8 bytes plus the index translation between them and Java's UTF-16
     * {@code String} indices. HarfBuzz reports clusters as byte offsets; the
     * rest of this program thinks in {@code String} indices.
     */
    private record Utf8(byte[] bytes, int[] byteToChar, int[] charToByte) {

        static Utf8 encode(String text) {
            byte[] buffer = new byte[text.length() * 3 + 4];
            int[] byteToChar = new int[buffer.length + 1];
            int[] charToByte = new int[text.length() + 1];
            int b = 0;
            int c = 0;
            while (c < text.length()) {
                int codePoint = text.codePointAt(c);
                int charCount = Character.charCount(codePoint);
                charToByte[c] = b;
                if (codePoint < 0x80) {
                    buffer[b++] = (byte) codePoint;
                } else if (codePoint < 0x800) {
                    buffer[b++] = (byte) (0xC0 | (codePoint >> 6));
                    buffer[b++] = (byte) (0x80 | (codePoint & 0x3F));
                } else if (Character.isSurrogate((char) codePoint)) {
                    buffer[b++] = '?'; // unpaired surrogate: not representable
                } else if (codePoint < 0x10000) {
                    buffer[b++] = (byte) (0xE0 | (codePoint >> 12));
                    buffer[b++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                    buffer[b++] = (byte) (0x80 | (codePoint & 0x3F));
                } else {
                    buffer[b++] = (byte) (0xF0 | (codePoint >> 18));
                    buffer[b++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                    buffer[b++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                    buffer[b++] = (byte) (0x80 | (codePoint & 0x3F));
                }
                for (int k = charToByte[c]; k < b; k++) {
                    byteToChar[k] = c;
                }
                for (int k = c + 1; k < c + charCount; k++) {
                    charToByte[k] = charToByte[c];
                }
                c += charCount;
            }
            charToByte[text.length()] = b;
            byteToChar[b] = text.length();
            return new Utf8(java.util.Arrays.copyOf(buffer, b), java.util.Arrays.copyOf(byteToChar, b + 1), charToByte);
        }
    }
}
