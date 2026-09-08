package com.example.banglapdf;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.example.banglapdf.TextLayout.Page;

/**
 * Turns laid-out pages into the object graph of a PDF file: a catalog, a page
 * tree, one content stream per page, and the composite font that lets glyph ids
 * be addressed directly.
 *
 * <p>The font is a {@code Type0} font with {@code Identity-H} encoding over a
 * {@code CIDFontType2} descendant whose {@code CIDToGIDMap} is
 * {@code /Identity}. Stacked up, those three choices mean one thing: a
 * two-byte code in the content stream <em>is</em> a glyph index in the embedded
 * font. There is no character-to-glyph step left for the viewer to perform, and
 * therefore none for it to perform differently from the shaper.
 */
final class PdfDocumentWriter {

    private static final String FONT_RESOURCE = "F1";
    /** PDF dates look like {@code D:20260908143000+06'00'}. */
    private static final DateTimeFormatter PDF_DATE = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss");

    private final TrueTypeFont font;
    private final PdfStyle style;
    private final DocumentInfo info;

    PdfDocumentWriter(TrueTypeFont font, PdfStyle style, DocumentInfo info) {
        this.font = font;
        this.style = style;
        this.info = info;
    }

    byte[] write(List<Page> pages) {
        GlyphUsage usage = new GlyphUsage();
        ContentStreamBuilder content = new ContentStreamBuilder(font, style, FONT_RESOURCE, usage);

        // Content streams first: drawing the pages is what discovers which
        // glyphs the font tables below have to describe.
        List<byte[]> streams = new ArrayList<>(pages.size());
        for (Page page : pages) {
            streams.add(content.build(page));
        }

        PdfObjectWriter writer = new PdfObjectWriter();
        int catalog = writer.allocate();
        int pageTree = writer.allocate();
        int type0Font = writer.allocate();
        int cidFont = writer.allocate();
        int descriptor = writer.allocate();
        int fontFile = writer.allocate();
        int toUnicode = writer.allocate();
        int documentInfo = writer.allocate();

        int[] pageObjects = new int[pages.size()];
        int[] contentObjects = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            pageObjects[i] = writer.allocate();
            contentObjects[i] = writer.allocate();
        }

        writer.writeDictionary(catalog,
                "<< /Type /Catalog /Pages " + ref(pageTree)
                        + " /Lang " + PdfSyntax.unicodeString(style.language())
                        + " /ViewerPreferences << /DisplayDocTitle true >> >>");

        StringBuilder kids = new StringBuilder("[");
        for (int object : pageObjects) {
            kids.append(ref(object)).append(' ');
        }
        writer.writeDictionary(pageTree,
                "<< /Type /Pages /Count " + pages.size() + " /Kids " + kids.append(']') + " >>");

        String mediaBox = "[0 0 " + PdfSyntax.number(style.pageWidth()) + " " + PdfSyntax.number(style.pageHeight()) + "]";
        for (int i = 0; i < pages.size(); i++) {
            writer.writeDictionary(pageObjects[i],
                    "<< /Type /Page /Parent " + ref(pageTree)
                            + " /MediaBox " + mediaBox
                            + " /Resources << /Font << /" + FONT_RESOURCE + " " + ref(type0Font) + " >> >>"
                            + " /Contents " + ref(contentObjects[i]) + " >>");
            writer.writeStream(contentObjects[i], "", streams.get(i));
        }

        writer.writeDictionary(type0Font,
                "<< /Type /Font /Subtype /Type0"
                        + " /BaseFont /" + font.postScriptName
                        + " /Encoding /Identity-H"
                        + " /DescendantFonts [" + ref(cidFont) + "]"
                        + " /ToUnicode " + ref(toUnicode) + " >>");

        writer.writeDictionary(cidFont,
                "<< /Type /Font /Subtype /CIDFontType2"
                        + " /BaseFont /" + font.postScriptName
                        + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                        + " /FontDescriptor " + ref(descriptor)
                        + " /DW 1000"
                        + " /W " + widthArray(usage)
                        + " /CIDToGIDMap /Identity >>");

        writer.writeDictionary(descriptor,
                "<< /Type /FontDescriptor"
                        + " /FontName /" + font.postScriptName
                        + " /Flags " + descriptorFlags()
                        + " /FontBBox [" + font.toThousandths(font.xMin) + " " + font.toThousandths(font.yMin)
                        + " " + font.toThousandths(font.xMax) + " " + font.toThousandths(font.yMax) + "]"
                        + " /ItalicAngle " + PdfSyntax.number(font.italicAngle)
                        + " /Ascent " + font.toThousandths(font.ascender)
                        + " /Descent " + font.toThousandths(font.descender)
                        + " /CapHeight " + font.toThousandths(font.ascender)
                        + " /StemV 80"
                        + " /FontFile2 " + ref(fontFile) + " >>");

        // Length1 is the *uncompressed* size of the font program, which is what
        // a reader needs after it has inflated the stream.
        writer.writeStream(fontFile, "/Length1 " + font.bytes.length, font.bytes);
        writer.writeStream(toUnicode, "", ToUnicodeCMap.build(usage));
        writer.writeDictionary(documentInfo, infoDictionary());

        return writer.finish(catalog, documentInfo);
    }

    /**
     * {@code /W} lists a width for every glyph the document draws, and for no
     * others. Consecutive glyph ids are grouped into one array, which is both
     * the compact form and the one most readers parse fastest.
     */
    private String widthArray(GlyphUsage usage) {
        StringBuilder widths = new StringBuilder("[");
        List<Integer> ids = new ArrayList<>(usage.glyphIds());
        int i = 0;
        while (i < ids.size()) {
            int runStart = i;
            while (i + 1 < ids.size() && ids.get(i + 1) == ids.get(i) + 1) {
                i++;
            }
            widths.append(ids.get(runStart)).append(" [");
            for (int j = runStart; j <= i; j++) {
                widths.append(font.widthInThousandths(ids.get(j)));
                if (j < i) {
                    widths.append(' ');
                }
            }
            widths.append("] ");
            i++;
        }
        return widths.append(']').toString();
    }

    /** Bit 3 marks the font as symbolic, which is correct for an Identity-H CID font. */
    private int descriptorFlags() {
        int flags = 4;
        if (font.italicAngle != 0) {
            flags |= 64;
        }
        return flags;
    }

    private String infoDictionary() {
        StringBuilder dict = new StringBuilder("<< ");
        appendIfPresent(dict, "Title", info.title());
        appendIfPresent(dict, "Author", info.author());
        dict.append("/Creator ").append(PdfSyntax.unicodeString("bangla-pdf")).append(' ');
        dict.append("/Producer ").append(PdfSyntax.unicodeString("bangla-pdf (HarfBuzz shaping, no PDF library)")).append(' ');
        dict.append("/CreationDate (").append(nowAsPdfDate()).append(") ");
        return dict.append(">>").toString();
    }

    private static String nowAsPdfDate() {
        OffsetDateTime now = OffsetDateTime.now();
        int offsetMinutes = now.getOffset().getTotalSeconds() / 60;
        String sign = offsetMinutes < 0 ? "-" : "+";
        offsetMinutes = Math.abs(offsetMinutes);
        return String.format("%s%s%02d'%02d'", PDF_DATE.format(now), sign, offsetMinutes / 60, offsetMinutes % 60);
    }

    private static void appendIfPresent(StringBuilder dict, String key, String value) {
        if (value != null && !value.isBlank()) {
            dict.append('/').append(key).append(' ').append(PdfSyntax.unicodeString(value)).append(' ');
        }
    }

    private static String ref(int objectNumber) {
        return objectNumber + " 0 R";
    }

    /** Optional document metadata, recorded in the PDF's Info dictionary. */
    record DocumentInfo(String title, String author) {

        static final DocumentInfo NONE = new DocumentInfo(null, null);
    }
}
