package com.example.banglapdf;

/**
 * Page geometry and typography for a rendered document. Immutable; every
 * {@code with...} method returns a new instance, so styles can be shared and
 * derived freely:
 *
 * <pre>{@code
 * PdfStyle style = PdfStyle.a4().withFontSize(18).withLanguage("bn-BD");
 * }</pre>
 *
 * <p>All measurements are in PostScript points (72 per inch), the unit PDF
 * itself uses.
 *
 * @param pageWidth        page width in points
 * @param pageHeight       page height in points
 * @param marginTop        top margin in points
 * @param marginRight      right margin in points
 * @param marginBottom     bottom margin in points
 * @param marginLeft       left margin in points
 * @param fontSize         type size in points
 * @param lineHeight       baseline-to-baseline distance as a multiple of {@code fontSize}
 * @param paragraphSpacing blank space between paragraphs, as a multiple of {@code fontSize}
 * @param language         BCP-47 tag handed to the shaper and recorded in the PDF
 */
public record PdfStyle(
        float pageWidth,
        float pageHeight,
        float marginTop,
        float marginRight,
        float marginBottom,
        float marginLeft,
        float fontSize,
        float lineHeight,
        float paragraphSpacing,
        String language) {

    /** A4 portrait: 595.28 x 841.89 points. */
    public static final float A4_WIDTH = 595.28f;
    public static final float A4_HEIGHT = 841.89f;

    public PdfStyle {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException("Page size must be positive");
        }
        if (fontSize <= 0) {
            throw new IllegalArgumentException("Font size must be positive");
        }
        if (marginLeft + marginRight >= pageWidth || marginTop + marginBottom >= pageHeight) {
            throw new IllegalArgumentException("Margins leave no room for text");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("Language must be a BCP-47 tag such as \"bn\"");
        }
    }

    /**
     * A4 with 56pt (~2cm) margins, 14pt type and 1.7x leading. Bangla stacks
     * vowel signs above and below the baseline and hangs conjuncts below it,
     * so it needs noticeably more leading than Latin text at the same size.
     */
    public static PdfStyle a4() {
        return new PdfStyle(A4_WIDTH, A4_HEIGHT, 56f, 56f, 56f, 56f, 14f, 1.7f, 0.7f, "bn");
    }

    /** Sets all four margins at once. */
    public PdfStyle withMargins(float margin) {
        return new PdfStyle(pageWidth, pageHeight, margin, margin, margin, margin,
                fontSize, lineHeight, paragraphSpacing, language);
    }

    public PdfStyle withFontSize(float size) {
        return new PdfStyle(pageWidth, pageHeight, marginTop, marginRight, marginBottom, marginLeft,
                size, lineHeight, paragraphSpacing, language);
    }

    /** Baseline-to-baseline distance as a multiple of the font size. */
    public PdfStyle withLineHeight(float multiple) {
        return new PdfStyle(pageWidth, pageHeight, marginTop, marginRight, marginBottom, marginLeft,
                fontSize, multiple, paragraphSpacing, language);
    }

    /**
     * BCP-47 language tag. This is not cosmetic: OpenType fonts carry
     * language-specific substitutions, and Assamese ({@code as}) legitimately
     * draws U+09B0 and U+09AF differently from Bangla ({@code bn}).
     */
    public PdfStyle withLanguage(String bcp47) {
        return new PdfStyle(pageWidth, pageHeight, marginTop, marginRight, marginBottom, marginLeft,
                fontSize, lineHeight, paragraphSpacing, bcp47);
    }

    /** Width available to text after margins. */
    public float textWidth() {
        return pageWidth - marginLeft - marginRight;
    }

    /** Baseline-to-baseline distance in points. */
    public float leading() {
        return fontSize * lineHeight;
    }

    /** Vertical gap inserted between paragraphs, in points. */
    public float paragraphGap() {
        return fontSize * paragraphSpacing;
    }

    /** Baseline of the first line on a page. */
    public float firstBaseline() {
        return pageHeight - marginTop - fontSize;
    }

    /** Lowest baseline that still fits above the bottom margin. */
    public float lastBaseline() {
        return marginBottom;
    }
}
