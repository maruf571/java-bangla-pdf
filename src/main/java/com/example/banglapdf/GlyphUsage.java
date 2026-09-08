package com.example.banglapdf;

import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Records which glyphs a document actually draws, and what text each of them
 * stands for.
 *
 * <p>The first fact keeps the PDF small: only the glyphs in use need a width
 * entry. The second is what makes the text selectable -- a PDF that draws
 * glyph ids and says nothing else is a picture of words, and search, copy and
 * screen readers all come up empty.
 */
final class GlyphUsage {

    private final NavigableSet<Integer> glyphIds = new TreeSet<>();
    private final Map<Integer, String> textByGlyph = new TreeMap<>();

    /**
     * Notes that a glyph is drawn, optionally with the text it represents.
     * The first meaningful text recorded for a glyph wins; a glyph reached
     * through several different clusters keeps its simplest reading, and the
     * ambiguous cases are handled precisely by {@code ActualText} spans in the
     * content stream instead.
     */
    void record(int glyphId, String text) {
        glyphIds.add(glyphId);
        if (text != null && !text.isEmpty() && !textByGlyph.containsKey(glyphId)) {
            textByGlyph.put(glyphId, text);
        }
    }

    /** Glyph ids in use, ascending. */
    NavigableSet<Integer> glyphIds() {
        return glyphIds;
    }

    /** Glyph id to source text, ascending by glyph id. */
    Map<Integer, String> textByGlyph() {
        return textByGlyph;
    }
}
