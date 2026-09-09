package com.example.banglapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;

import com.example.banglapdf.TextLayout.Page;

/**
 * Turns laid-out pages into an actual PDF file, using Apache PDFBox for
 * everything that is ordinary PDF bookkeeping: object numbering, the
 * cross-reference table, stream compression, and the font-descriptor
 * dictionary that comes from embedding a TrueType font.
 *
 * <p>What PDFBox does <em>not</em> do here is decide what to draw. Loading the
 * font with {@code embedSubset = false} embeds it whole and, as a side effect,
 * makes PDFBox build the font's {@code /W} widths and {@code /ToUnicode} CMap
 * for every glyph in it up front -- both straight from the font's own tables,
 * the same way this project used to by hand. The font is also set up as
 * {@code /Encoding /Identity-H} over a {@code CIDFontType2} with
 * {@code /CIDToGIDMap /Identity}, so a two-byte code in the content stream
 * <em>is</em> a glyph index in the embedded font, with no character-to-glyph
 * step left for the viewer to perform differently than the shaper did.
 *
 * <p>The one thing PDFBox is not allowed to touch is the text itself: its
 * normal {@code showText} would re-shape nothing and just re-encode the string
 * through the font's cmap, which is exactly the naive path that breaks Bangla.
 * So each page's content stream is opened and closed through PDFBox as usual,
 * but the text-showing operators inside it are the exact bytes
 * {@link ContentStreamBuilder} built from HarfBuzz's glyph ids, injected with
 * {@code appendRawCommands}.
 */
final class PdfDocumentBuilder {

    private final byte[] fontBytes;
    private final TrueTypeFont fontMetrics;
    private final PdfStyle style;
    private final DocumentInfo info;

    PdfDocumentBuilder(byte[] fontBytes, TrueTypeFont fontMetrics, PdfStyle style, DocumentInfo info) {
        this.fontBytes = fontBytes;
        this.fontMetrics = fontMetrics;
        this.style = style;
        this.info = info;
    }

    byte[] write(List<Page> pages) {
        try (PDDocument document = new PDDocument()) {
            // false: embed the whole font rather than a PDFBox-computed
            // subset. Subsetting is driven by PDFBox's own showText/encode
            // bookkeeping, which this project deliberately bypasses -- see
            // the class-level note on why the content stream is written by
            // hand. Embedding whole also means PDFBox's automatic ToUnicode
            // build (see below) covers every glyph, not just the ones a
            // particular document happens to use.
            PDType0Font font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), false);

            document.getDocumentInformation().setCreator("bangla-pdf");
            document.getDocumentInformation().setProducer("bangla-pdf (HarfBuzz shaping, PDFBox assembly)");
            document.getDocumentInformation().setCreationDate(Calendar.getInstance());
            if (info.title() != null) {
                document.getDocumentInformation().setTitle(info.title());
            }
            if (info.author() != null) {
                document.getDocumentInformation().setAuthor(info.author());
            }
            document.getDocumentCatalog().setLanguage(style.language());
            PDViewerPreferences viewerPreferences = new PDViewerPreferences(new COSDictionary());
            viewerPreferences.setDisplayDocTitle(true);
            document.getDocumentCatalog().setViewerPreferences(viewerPreferences);

            PDRectangle mediaBox = new PDRectangle(style.pageWidth(), style.pageHeight());
            ContentStreamBuilder content = new ContentStreamBuilder(fontMetrics, style);

            for (Page page : pages) {
                PDPage pdPage = new PDPage(mediaBox);
                document.addPage(pdPage);
                try (PDPageContentStream stream = new PDPageContentStream(document, pdPage)) {
                    // Table grid lines are path-stroking operators and must sit
                    // outside the text object; order relative to the text
                    // itself does not matter since the two never overlap.
                    String rules = content.buildRules(page);
                    if (!rules.isEmpty()) {
                        stream.appendRawCommands(rules);
                    }
                    stream.beginText();
                    stream.setFont(font, style.fontSize());
                    stream.appendRawCommands(content.buildText(page));
                    stream.endText();
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BanglaPdfException("Could not assemble the PDF", e);
        }
    }

    /** Optional document metadata, recorded in the PDF's Info dictionary. */
    record DocumentInfo(String title, String author) {

        static final DocumentInfo NONE = new DocumentInfo(null, null);
    }
}
