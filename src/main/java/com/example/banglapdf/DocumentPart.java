package com.example.banglapdf;

import java.util.List;
import java.util.Objects;

/**
 * One piece of a {@link Document}: a paragraph of prose, or a table.
 *
 * <p>Plain paragraphs -- what {@link BanglaPdfService#write(String, java.nio.file.Path)}
 * has always taken -- are all {@link TextLayout} used to know how to lay out:
 * left-aligned, flowing, wrapped to the column. An invoice needs more than
 * that -- a line-item list with a quantity column and a price column that
 * actually line up -- so {@link Table} gives {@link TextLayout} fixed-width
 * columns and per-cell alignment to lay out instead, with grid lines drawn
 * around the result. Nothing about shaping changes: every cell is wrapped and
 * shaped exactly like a narrow paragraph, so a long item name still breaks at
 * cluster boundaries rather than mid-conjunct.
 */
public sealed interface DocumentPart {

    /** A paragraph of prose, wrapped and flowed exactly like {@code write(String, Path)}. */
    record Paragraph(String text) implements DocumentPart {
        public Paragraph {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A table with fixed-width columns. A cell that does not fit its column
     * wraps, the same way a paragraph wraps to the page; a row's height is
     * whichever of its cells wraps to the most lines, and a row is never split
     * across a page break -- if it does not fit, the whole row moves to a new
     * page.
     *
     * @param columns    left-to-right column definitions; their combined width
     *                   plus {@code indent} must not exceed the text column
     * @param rows       each row's cells, one per column, in column order
     * @param showHeader whether a header row (each column's {@code header})
     *                   is drawn before the first data row -- false for a
     *                   label/value summary block that does not need one
     * @param indent     horizontal offset from the left margin, in points --
     *                   0 for a full-width table, or enough to right-anchor a
     *                   narrower one (a totals block under an items table, say)
     */
    record Table(List<Column> columns, List<List<String>> rows, boolean showHeader, float indent)
            implements DocumentPart {
        public Table {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            if (columns.isEmpty()) {
                throw new BanglaPdfException("A table needs at least one column");
            }
            for (List<String> row : rows) {
                if (row.size() != columns.size()) {
                    throw new BanglaPdfException(
                            "A table row has " + row.size() + " cells but the table has "
                                    + columns.size() + " columns");
                }
            }
            if (indent < 0) {
                throw new BanglaPdfException("Table indent must not be negative, was " + indent);
            }
        }

        /** A full-width table with a header row -- the common case. */
        public Table(List<Column> columns, List<List<String>> rows) {
            this(columns, rows, true, 0);
        }
    }

    /** How a column's cell text sits within its width. */
    enum Alignment {
        /** Flush against the column's left edge -- names, descriptions. */
        LEFT,
        /** Flush against the column's right edge -- quantities, money. */
        RIGHT
    }

    /**
     * One table column: its header label, its fixed width in points, and how
     * its cells align within that width.
     */
    record Column(String header, float width, Alignment alignment) {
        public Column {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(alignment, "alignment");
            if (width <= 0) {
                throw new BanglaPdfException("Column width must be positive, was " + width);
            }
        }

        /** A left-aligned column: the natural choice for names and descriptions. */
        public static Column left(String header, float width) {
            return new Column(header, width, Alignment.LEFT);
        }

        /** A right-aligned column: the natural choice for quantities and money. */
        public static Column right(String header, float width) {
            return new Column(header, width, Alignment.RIGHT);
        }
    }
}
