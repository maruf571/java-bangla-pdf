package com.example.banglapdf;

import java.util.ArrayList;
import java.util.List;

import com.example.banglapdf.TextShaper.Cluster;
import com.example.banglapdf.TextShaper.ShapedText;

/**
 * Breaks paragraphs into lines that fit the text column, then flows those lines
 * onto pages.
 *
 * <p>Line breaking is done on shaped widths rather than character counts, which
 * is the only way to get it right for Bangla: the visual width of a cluster has
 * very little to do with how many code points went into it, since ক্ষ is three
 * characters drawn as one glyph while কি is two characters drawn as two glyphs
 * in the opposite order.
 */
final class TextLayout {

    /** One line of shaped text, positioned on its page. */
    record Line(float x, float baseline, ShapedText text) {
    }

    /** One page's worth of lines. */
    record Page(List<Line> lines) {
    }

    private final TextShaper shaper;
    private final PdfStyle style;

    TextLayout(TextShaper shaper, PdfStyle style) {
        this.shaper = shaper;
        this.style = style;
    }

    /**
     * Lays out paragraphs into pages. Paragraphs are separated by vertical
     * space; a newline inside a paragraph is honoured as a hard line break.
     */
    List<Page> paginate(List<String> paragraphs) {
        List<Page> pages = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        float baseline = style.firstBaseline();
        boolean firstParagraph = true;

        for (String paragraph : paragraphs) {
            if (!firstParagraph) {
                baseline -= style.paragraphGap();
            }
            firstParagraph = false;

            for (ShapedText line : layOutParagraph(paragraph)) {
                if (baseline < style.lastBaseline() && !current.isEmpty()) {
                    pages.add(new Page(List.copyOf(current)));
                    current.clear();
                    baseline = style.firstBaseline();
                }
                current.add(new Line(style.marginLeft(), baseline, line));
                baseline -= style.leading();
            }
        }

        if (!current.isEmpty() || pages.isEmpty()) {
            pages.add(new Page(List.copyOf(current)));
        }
        return List.copyOf(pages);
    }

    private List<ShapedText> layOutParagraph(String paragraph) {
        List<ShapedText> lines = new ArrayList<>();
        for (String hardLine : paragraph.split("\n", -1)) {
            lines.addAll(wrap(hardLine.strip()));
        }
        return lines;
    }

    /** Greedy first-fit wrapping at space boundaries. */
    private List<ShapedText> wrap(String text) {
        if (text.isEmpty()) {
            return List.of(shaper.shape(""));
        }
        float maxWidth = style.textWidth();
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
}
