package com.example.banglapdf;

import java.util.ArrayList;
import java.util.List;

import com.example.banglapdf.DocumentPart.Alignment;
import com.example.banglapdf.DocumentPart.Column;
import com.example.banglapdf.TextShaper.Cluster;
import com.example.banglapdf.TextShaper.ShapedText;

/**
 * Breaks a {@link Document}'s parts into lines and rules that fit the text
 * column, then flows them onto pages.
 *
 * <p>Line breaking is done on shaped widths rather than character counts, which
 * is the only way to get it right for Bangla: the visual width of a cluster has
 * very little to do with how many code points went into it, since ক্ষ is three
 * characters drawn as one glyph while কি is two characters drawn as two glyphs
 * in the opposite order. A table cell wraps by the same rule, just to a
 * narrower width than the full column.
 */
final class TextLayout {

    /** One line of shaped text, positioned on its page. */
    record Line(float x, float baseline, ShapedText text) {
    }

    /** One straight stroked segment, in page points (used to draw a table's grid). */
    record Rule(float x1, float y1, float x2, float y2) {
    }

    /** One page's worth of lines and rules. */
    record Page(List<Line> lines, List<Rule> rules) {
    }

    /**
     * Row geometry, as multiples of font size: tighter than prose leading
     * (which is tuned for flowing text), but the ascent/descent gaps still
     * have to clear the font's own ascender/descender (Noto Sans Bengali's
     * are 0.92 and 0.41 respectively -- Bangla's stacked matras use most of
     * that) plus a little padding, or a grid rule ends up drawn through the
     * glyphs instead of around them.
     */
    private static final float TABLE_LINE_HEIGHT = 1.5f;
    private static final float TABLE_ASCENT_GAP = 1.0f;
    private static final float TABLE_DESCENT_GAP = 0.5f;
    private static final float TABLE_CELL_PADDING = 0.3f;

    private final TextShaper shaper;
    private final PdfStyle style;

    TextLayout(TextShaper shaper, PdfStyle style) {
        this.shaper = shaper;
        this.style = style;
    }

    /**
     * Lays out a document's parts into pages. Parts are separated by the same
     * vertical space paragraphs are; a newline inside a paragraph is honoured
     * as a hard line break; a table's rows are laid out into a grid and never
     * split across a page break.
     */
    List<Page> paginate(List<DocumentPart> parts) {
        PageAccumulator acc = new PageAccumulator();
        boolean first = true;

        for (DocumentPart part : parts) {
            if (!first) {
                acc.advanceBaseline(style.paragraphGap());
            }
            first = false;

            switch (part) {
                case DocumentPart.Paragraph p -> layOutParagraphInto(acc, p.text());
                case DocumentPart.Table t -> layOutTableInto(acc, t);
            }
        }

        return acc.finish();
    }

    private void layOutParagraphInto(PageAccumulator acc, String paragraph) {
        for (ShapedText line : layOutParagraph(paragraph)) {
            acc.breakIfNeeded(acc.baseline());
            acc.addLine(style.marginLeft(), acc.baseline(), line);
            acc.advanceBaseline(style.leading());
        }
    }

    private List<ShapedText> layOutParagraph(String paragraph) {
        List<ShapedText> lines = new ArrayList<>();
        for (String hardLine : paragraph.split("\n", -1)) {
            lines.addAll(wrap(hardLine.strip(), style.textWidth()));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    private void layOutTableInto(PageAccumulator acc, DocumentPart.Table table) {
        List<Column> columns = table.columns();
        float[] colX = new float[columns.size()];
        float cursor = style.marginLeft() + table.indent();
        for (int i = 0; i < columns.size(); i++) {
            colX[i] = cursor;
            cursor += columns.get(i).width();
        }
        float tableRight = cursor;
        if (tableRight - style.marginLeft() > style.textWidth() + 0.5f) {
            throw new BanglaPdfException(
                    "Table columns plus indent are " + (tableRight - style.marginLeft())
                            + "pt wide, wider than the " + style.textWidth() + "pt text column");
        }

        float cellPadding = style.fontSize() * TABLE_CELL_PADDING;
        float ascentGap = style.fontSize() * TABLE_ASCENT_GAP;
        float descentGap = style.fontSize() * TABLE_DESCENT_GAP;
        float lineHeight = style.fontSize() * TABLE_LINE_HEIGHT;

        boolean[] startOfSegment = {true};
        if (table.showHeader()) {
            List<String> header = columns.stream().map(Column::header).toList();
            layOutTableRow(acc, columns, colX, tableRight, header, cellPadding, ascentGap, descentGap, lineHeight, startOfSegment);
        }
        for (List<String> row : table.rows()) {
            layOutTableRow(acc, columns, colX, tableRight, row, cellPadding, ascentGap, descentGap, lineHeight, startOfSegment);
        }
    }

    /**
     * Lays out one table row -- header or body -- as a self-contained grid
     * cell: its own top/bottom/side rules, sized to whichever of its cells
     * wraps to the most lines. {@code startOfSegment} tracks whether the next
     * row drawn needs a top border, which is true for the table's first row
     * and again for the first row after a page break.
     */
    private void layOutTableRow(PageAccumulator acc, List<Column> columns, float[] colX, float tableRight,
                                 List<String> cellTexts, float cellPadding, float ascentGap, float descentGap,
                                 float lineHeight, boolean[] startOfSegment) {
        List<List<ShapedText>> cellLines = new ArrayList<>(columns.size());
        int maxLines = 1;
        for (int i = 0; i < columns.size(); i++) {
            float maxWidth = Math.max(1f, columns.get(i).width() - 2 * cellPadding);
            List<ShapedText> wrapped = wrap(cellTexts.get(i), maxWidth);
            cellLines.add(wrapped);
            maxLines = Math.max(maxLines, wrapped.size());
        }

        RowGeometry row = RowGeometry.of(acc.baseline(), maxLines, ascentGap, descentGap, lineHeight);
        if (acc.breakIfNeeded(row.bottomY())) {
            startOfSegment[0] = true;
            row = RowGeometry.of(acc.baseline(), maxLines, ascentGap, descentGap, lineHeight);
        }

        if (startOfSegment[0]) {
            acc.addRule(colX[0], row.topY(), tableRight, row.topY());
            startOfSegment[0] = false;
        }

        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            List<ShapedText> lines = cellLines.get(i);
            for (int k = 0; k < lines.size(); k++) {
                ShapedText text = lines.get(k);
                float baseline = row.firstBaseline() - k * lineHeight;
                float textWidth = text.width(style.fontSize());
                float x = column.alignment() == Alignment.RIGHT
                        ? colX[i] + column.width() - cellPadding - textWidth
                        : colX[i] + cellPadding;
                acc.addLine(x, baseline, text);
            }
        }

        for (int i = 0; i <= columns.size(); i++) {
            float x = i < columns.size() ? colX[i] : tableRight;
            acc.addRule(x, row.topY(), x, row.bottomY());
        }
        acc.addRule(colX[0], row.bottomY(), tableRight, row.bottomY());

        acc.setBaseline(row.bottomY() - ascentGap);
    }

    /** The vertical geometry of one table row, derived once from its first baseline and line count. */
    private record RowGeometry(float firstBaseline, float topY, float bottomY) {
        static RowGeometry of(float firstBaseline, int lineCount, float ascentGap, float descentGap, float lineHeight) {
            float lastBaseline = firstBaseline - (lineCount - 1) * lineHeight;
            return new RowGeometry(firstBaseline, firstBaseline + ascentGap, lastBaseline - descentGap);
        }
    }

    // ------------------------------------------------------------------
    // Wrapping, shared by paragraphs and table cells
    // ------------------------------------------------------------------

    /** Greedy first-fit wrapping at space boundaries, to an arbitrary width. */
    private List<ShapedText> wrap(String text, float maxWidth) {
        if (text.isEmpty()) {
            return List.of(shaper.shape(""));
        }
        float spaceWidth = shaper.shape(" ").width(style.fontSize());

        List<ShapedText> lines = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        float pendingWidth = 0;

        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            // Each word is measured once, and the assembled line is shaped once
            // more when it is emitted -- linear in the length of the paragraph.
            float wordWidth = shaper.shape(word).width(style.fontSize());
            float candidateWidth = pending.isEmpty() ? wordWidth : pendingWidth + spaceWidth + wordWidth;

            if (!pending.isEmpty() && candidateWidth > maxWidth) {
                lines.add(shaper.shape(pending.toString()));
                pending.setLength(0);
                pendingWidth = 0;
                candidateWidth = wordWidth;
            }
            if (pending.isEmpty() && wordWidth > maxWidth) {
                List<String> pieces = breakOversizedWord(word, maxWidth);
                for (int i = 0; i < pieces.size() - 1; i++) {
                    lines.add(shaper.shape(pieces.get(i)));
                }
                String tail = pieces.getLast();
                pending.append(tail);
                pendingWidth = shaper.shape(tail).width(style.fontSize());
                continue;
            }

            if (!pending.isEmpty()) {
                pending.append(' ');
            }
            pending.append(word);
            pendingWidth = candidateWidth;
        }

        if (!pending.isEmpty()) {
            lines.add(shaper.shape(pending.toString()));
        }
        return lines;
    }

    /**
     * Splits a word too long for the column. The cut points are cluster
     * boundaries, never arbitrary character positions: cutting inside ক্ষ would
     * leave a dangling hasant and change what the reader sees.
     */
    private List<String> breakOversizedWord(String word, float maxWidth) {
        List<String> pieces = new ArrayList<>();
        ShapedText shaped = shaper.shape(word);
        int pieceStart = 0;
        float width = 0;

        for (Cluster cluster : shaped.clusters()) {
            float clusterWidth = 0;
            for (int i = cluster.firstGlyph(); i < cluster.firstGlyph() + cluster.glyphCount(); i++) {
                clusterWidth += shaped.glyphs().get(i).xAdvance();
            }
            clusterWidth = clusterWidth * style.fontSize() / shaped.unitsPerEm();

            if (width + clusterWidth > maxWidth && cluster.sourceStart() > pieceStart) {
                pieces.add(word.substring(pieceStart, cluster.sourceStart()));
                pieceStart = cluster.sourceStart();
                width = 0;
            }
            width += clusterWidth;
        }
        pieces.add(word.substring(pieceStart));
        return pieces;
    }

    /**
     * Tracks the current page's lines and rules and the next free baseline,
     * breaking to a new page whenever what comes next would not fit.
     */
    private final class PageAccumulator {

        private final List<Page> pages = new ArrayList<>();
        private List<Line> lines = new ArrayList<>();
        private List<Rule> rules = new ArrayList<>();
        private float baseline = style.firstBaseline();

        float baseline() {
            return baseline;
        }

        void setBaseline(float newBaseline) {
            baseline = newBaseline;
        }

        void advanceBaseline(float delta) {
            baseline -= delta;
        }

        /**
         * Starts a new page if {@code requiredBottomY} -- the lowest point on
         * the page what comes next will need -- falls below the margin, unless
         * the page is still empty, in which case a new page would not help.
         *
         * @return true if a page break happened
         */
        boolean breakIfNeeded(float requiredBottomY) {
            if (requiredBottomY < style.lastBaseline() && (!lines.isEmpty() || !rules.isEmpty())) {
                flushPage();
                return true;
            }
            return false;
        }

        void addLine(float x, float baseline, ShapedText text) {
            lines.add(new Line(x, baseline, text));
        }

        void addRule(float x1, float y1, float x2, float y2) {
            rules.add(new Rule(x1, y1, x2, y2));
        }

        private void flushPage() {
            pages.add(new Page(List.copyOf(lines), List.copyOf(rules)));
            lines = new ArrayList<>();
            rules = new ArrayList<>();
            baseline = style.firstBaseline();
        }

        List<Page> finish() {
            if (!lines.isEmpty() || !rules.isEmpty() || pages.isEmpty()) {
                pages.add(new Page(List.copyOf(lines), List.copyOf(rules)));
            }
            return List.copyOf(pages);
        }
    }
}
