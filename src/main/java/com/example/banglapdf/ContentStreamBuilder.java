package com.example.banglapdf;

import java.util.List;

import com.example.banglapdf.TextLayout.Line;
import com.example.banglapdf.TextLayout.Page;
import com.example.banglapdf.TextShaper.Cluster;
import com.example.banglapdf.TextShaper.ShapedGlyph;
import com.example.banglapdf.TextShaper.ShapedText;

/**
 * Writes a page's shaped glyphs as a PDF content stream.
 *
 * <p>Three details do the real work here:
 *
 * <ul>
 *   <li><b>Identity-H codes.</b> Each glyph is emitted as its two-byte glyph
 *       id. Nothing in the file asks the viewer to map characters to glyphs,
 *       so the viewer cannot get Bangla wrong -- the decision was already made
 *       by the shaper.
 *   <li><b>TJ adjustments and Ts.</b> HarfBuzz positions marks with a
 *       horizontal and vertical offset. The horizontal part is folded into the
 *       kerning numbers of a {@code TJ} array, and the vertical part becomes a
 *       text rise ({@code Ts}), so a whole line is usually one operator.
 *   <li><b>ActualText.</b> Any word whose glyphs do not correspond one-to-one
 *       with the characters they came from -- which in Bangla is most words --
 *       is wrapped in a marked-content span carrying the original characters,
 *       in their original order.
 * </ul>
 */
final class ContentStreamBuilder {

    private final TrueTypeFont font;
    private final PdfStyle style;
    private final String fontResource;
    private final GlyphUsage usage;

    ContentStreamBuilder(TrueTypeFont font, PdfStyle style, String fontResource, GlyphUsage usage) {
        this.font = font;
        this.style = style;
        this.fontResource = fontResource;
        this.usage = usage;
    }

    byte[] build(Page page) {
        StringBuilder out = new StringBuilder(4096);
        out.append("BT\n/").append(fontResource).append(' ')
                .append(PdfSyntax.number(style.fontSize())).append(" Tf\n");
        for (Line line : page.lines()) {
            if (line.text().isEmpty()) {
                continue;
            }
            // An absolute text matrix per line: no pen-tracking arithmetic to
            // drift, and each line is independently readable in the output.
            out.append("1 0 0 1 ").append(PdfSyntax.number(line.x())).append(' ')
                    .append(PdfSyntax.number(line.baseline())).append(" Tm\n");
            appendLine(out, line.text());
        }
        out.append("ET\n");
        return PdfSyntax.ascii(out.toString());
    }

    private void appendLine(StringBuilder out, ShapedText text) {
        GlyphRun run = new GlyphRun(out);
        List<Cluster> clusters = text.clusters();
        int i = 0;
        while (i < clusters.size()) {
            if (isSpace(text, clusters.get(i))) {
                appendClusters(run, text, i, i + 1);
                i++;
                continue;
            }
            int end = i;
            boolean ambiguous = false;
            while (end < clusters.size() && !isSpace(text, clusters.get(end))) {
                Cluster cluster = clusters.get(end);
                ambiguous |= cluster.glyphCount() > 1 || cluster.spansSeveralCharacters();
                end++;
            }

            if (ambiguous) {
                // The span covers the whole word rather than the individual
                // cluster. It is the same claim either way, but keeping it
                // whole means no text extractor has to decide what a gap
                // between two spans means -- and the sub-pixel backtracking
                // that positions a Bangla mark reads to some of them as a
                // space when it lands on a span boundary.
                String source = text.source().substring(
                        clusters.get(i).sourceStart(), clusters.get(end - 1).sourceEnd());
                run.flush();
                out.append("/Span << /ActualText ").append(PdfSyntax.unicodeString(source)).append(" >> BDC\n");
                appendClusters(run, text, i, end);
                run.flush();
                out.append("EMC\n");
            } else {
                appendClusters(run, text, i, end);
            }
            i = end;
        }
        run.finish();
    }

    private void appendClusters(GlyphRun run, ShapedText text, int from, int to) {
        for (int c = from; c < to; c++) {
            Cluster cluster = text.clusters().get(c);
            String source = text.textOf(cluster);
            for (int i = 0; i < cluster.glyphCount(); i++) {
                ShapedGlyph glyph = text.glyphs().get(cluster.firstGlyph() + i);
                // The font's own character map is authoritative where it has an
                // answer; the cluster's text is the fallback for glyphs that
                // exist only as the product of a substitution.
                String canonical = font.unicodeForGlyph(glyph.glyphId());
                usage.record(glyph.glyphId(), canonical != null ? canonical : (i == 0 ? source : null));
                run.append(glyph);
            }
        }
    }

    private static boolean isSpace(ShapedText text, Cluster cluster) {
        String source = text.textOf(cluster);
        return !source.isEmpty() && source.codePoints().allMatch(Character::isWhitespace);
    }

    /**
     * Accumulates consecutive glyphs into one {@code TJ} array, breaking out of
     * it only when a glyph needs a vertical offset.
     */
    private final class GlyphRun {

        private final StringBuilder out;
        private final StringBuilder items = new StringBuilder();
        private final StringBuilder codes = new StringBuilder();
        private float rise = 0;
        private int itemsOnLine = 0;

        GlyphRun(StringBuilder out) {
            this.out = out;
        }

        void append(ShapedGlyph glyph) {
            float wantedRise = glyph.yOffset() * style.fontSize() / font.unitsPerEm;
            if (wantedRise != rise) {
                flush();
                out.append(PdfSyntax.number(wantedRise)).append(" Ts\n");
                rise = wantedRise;
            }

            // Everything below is in thousandths of an em, the unit both PDF
            // glyph widths and TJ adjustments are expressed in.
            float scale = 1000f / font.unitsPerEm;
            float advance = glyph.xAdvance() * scale;
            float offset = glyph.xOffset() * scale;
            float builtInWidth = font.widthInThousandths(glyph.glyphId());

            // A number in a TJ array moves the pen left by n/1000 of the font
            // size, so shifting a mark to the right takes a negative number.
            adjust(-offset);
            codes.append(PdfSyntax.glyphCode(glyph.glyphId()));
            adjust(-(advance - offset - builtInWidth));
        }

        private void adjust(float thousandths) {
            if (Math.abs(thousandths) < 0.5f) {
                return; // below half a thousandth of an em: invisible, not worth the bytes
            }
            flushCodes();
            items.append(PdfSyntax.number(thousandths)).append(' ');
            wrapIfLong();
        }

        private void flushCodes() {
            if (codes.isEmpty()) {
                return;
            }
            items.append('<').append(codes).append("> ");
            codes.setLength(0);
            wrapIfLong();
        }

        private void wrapIfLong() {
            // Keep content-stream lines short enough for any reader and for a
            // human running `qpdf --qdf` over the result.
            if (++itemsOnLine >= 12) {
                items.append('\n');
                itemsOnLine = 0;
            }
        }

        void flush() {
            flushCodes();
            if (!items.isEmpty()) {
                out.append('[').append(items.toString().stripTrailing()).append("] TJ\n");
                items.setLength(0);
                itemsOnLine = 0;
            }
        }

        void finish() {
            flush();
            if (rise != 0) {
                out.append("0 Ts\n");
                rise = 0;
            }
        }
    }
}
