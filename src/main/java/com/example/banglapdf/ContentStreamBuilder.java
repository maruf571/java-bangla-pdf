package com.example.banglapdf;

import java.util.List;

import com.example.banglapdf.TextLayout.Line;
import com.example.banglapdf.TextLayout.Page;
import com.example.banglapdf.TextLayout.Rule;
import com.example.banglapdf.TextShaper.Cluster;
import com.example.banglapdf.TextShaper.ShapedGlyph;
import com.example.banglapdf.TextShaper.ShapedText;

/**
 * Writes a page's shaped glyphs as PDF text-showing operators.
 *
 * <p>This is deliberately not done through PDFBox's own text API. {@code
 * PDPageContentStream.showText(String)} re-encodes its argument through the
 * font's cmap, character by character -- exactly the naive, unshaped path this
 * project exists to avoid. So {@link PdfDocumentBuilder} opens the page's text
 * object and selects the font through PDFBox as normal, then hands the byte
 * sequence built here to {@code appendRawCommands}, which writes it verbatim.
 * Everything downstream of {@link TextShaper} already knows the exact glyph
 * ids and positions; nothing here re-derives them.
 *
 * <p>Three details do the real work:
 *
 * <ul>
 *   <li><b>Identity-H codes.</b> Each glyph is emitted as its own two-byte
 *       glyph id. Nothing in the file asks the viewer to map characters to
 *       glyphs, so the viewer cannot get Bangla wrong -- the decision was
 *       already made by the shaper.
 *   <li><b>TJ adjustments and Ts.</b> HarfBuzz positions marks with a
 *       horizontal and vertical offset. The horizontal part is folded into the
 *       kerning numbers of a {@code TJ} array, and the vertical part becomes a
 *       text rise ({@code Ts}), so a whole line is usually one operator.
 *   <li><b>ActualText.</b> Any word whose glyphs do not map one-to-one onto
 *       its characters -- which in Bangla is most words -- is wrapped in a
 *       marked-content span carrying the original characters, in their
 *       original order. (Per-glyph {@code ToUnicode} is PDFBox's job: with the
 *       font embedded unsubset, it builds one from the font's own cmap for
 *       every glyph that maps to a character on its own; ActualText covers
 *       what that cannot -- the drawing order.)
 * </ul>
 *
 * <p>A table's grid lines are a separate concern, built by {@link #buildRules}
 * as plain path-stroking operators (there is no text involved), and run before
 * the page's text object rather than inside it.
 */
final class ContentStreamBuilder {

    private final TrueTypeFont font;
    private final PdfStyle style;

    ContentStreamBuilder(TrueTypeFont font, PdfStyle style) {
        this.font = font;
        this.style = style;
    }

    /** The text-showing operators for one page, to run inside PDFBox's BT/Tf/ET. */
    String buildText(Page page) {
        StringBuilder out = new StringBuilder(4096);
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
        return out.toString();
    }

    /**
     * The stroke operators for one page's table grid lines, to run outside any
     * text object -- path-painting operators are not legal between {@code BT}
     * and {@code ET}. Returns an empty string for a page with no tables, so
     * callers can skip emitting it.
     */
    String buildRules(Page page) {
        if (page.rules().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(256);
        out.append("0.75 w 0 0 0 RG\n");
        for (Rule rule : page.rules()) {
            out.append(PdfSyntax.number(rule.x1())).append(' ').append(PdfSyntax.number(rule.y1())).append(" m ")
                    .append(PdfSyntax.number(rule.x2())).append(' ').append(PdfSyntax.number(rule.y2()))
                    .append(" l S\n");
        }
        return out.toString();
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
            for (int i = 0; i < cluster.glyphCount(); i++) {
                run.append(text.glyphs().get(cluster.firstGlyph() + i));
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
