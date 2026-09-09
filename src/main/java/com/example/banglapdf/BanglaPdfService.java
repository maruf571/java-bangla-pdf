package com.example.banglapdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.example.banglapdf.PdfDocumentBuilder.DocumentInfo;
import com.example.banglapdf.TextLayout.Page;

/**
 * Renders Unicode Bangla text to a PDF.
 *
 * <pre>{@code
 * try (BanglaPdfService pdf = BanglaPdfService.withBundledFont()) {
 *     pdf.write("আমি বাংলায় গান গাই।", Path.of("out.pdf"));
 * }
 * }</pre>
 *
 * <p>That is the whole API. Behind it, the text is shaped by HarfBuzz (not
 * delegated to any library -- that is the step that makes the Bangla correct),
 * wrapped to the column, flowed onto pages, and assembled into a PDF by Apache
 * PDFBox with the font embedded and searchable, copyable text. See {@code
 * README.md} for why each step is needed and what would break without it.
 *
 * <p>Style and metadata are set with {@code with...} methods, each returning a
 * new service that shares the same loaded font:
 *
 * <pre>{@code
 * pdf.withStyle(PdfStyle.a4().withFontSize(18))
 *    .withTitle("বার্ষিক প্রতিবেদন")
 *    .write(paragraphs, Path.of("report.pdf"));
 * }</pre>
 *
 * <p>For a document that needs a real table -- an invoice's line items, a
 * price list -- {@link #write(Document, Path)} takes a {@link Document} of
 * {@link DocumentPart}s (prose and tables, in order) instead of paragraphs.
 *
 * <p>Instances hold native memory. Close the service when you are done with it;
 * derived instances share the parent's font, so closing any one of them closes
 * them all. Instances are not thread-safe.
 */
public final class BanglaPdfService implements AutoCloseable {

    private static final String BUNDLED_FONT = "/fonts/NotoSansBengali-Regular.ttf";

    private final LoadedFont font;
    private final PdfStyle style;
    private final DocumentInfo info;

    private BanglaPdfService(LoadedFont font, PdfStyle style, DocumentInfo info) {
        this.font = font;
        this.style = style;
        this.info = info;
    }

    /**
     * Uses the Bangla font shipped on the classpath (Noto Sans Bengali), with
     * A4 pages and sensible Bangla typography.
     */
    public static BanglaPdfService withBundledFont() {
        return withFont(readBundledFont());
    }

    /** Uses a TrueType font from disk. It must have TrueType (glyf) outlines. */
    public static BanglaPdfService withFont(Path trueTypeFont) {
        Objects.requireNonNull(trueTypeFont, "trueTypeFont");
        try {
            return withFont(Files.readAllBytes(trueTypeFont));
        } catch (IOException e) {
            throw new BanglaPdfException("Could not read the font at " + trueTypeFont, e);
        }
    }

    /** Uses a TrueType font already in memory. */
    public static BanglaPdfService withFont(byte[] trueTypeFont) {
        Objects.requireNonNull(trueTypeFont, "trueTypeFont");
        PdfStyle style = PdfStyle.a4();
        return new BanglaPdfService(new LoadedFont(trueTypeFont, style.language()), style, DocumentInfo.NONE);
    }

    /**
     * Returns a service with different page geometry or typography. Changing
     * the language re-opens the shaper, since language selects OpenType
     * features.
     */
    public BanglaPdfService withStyle(PdfStyle newStyle) {
        Objects.requireNonNull(newStyle, "style");
        if (!newStyle.language().equals(style.language())) {
            font.switchLanguage(newStyle.language());
        }
        return new BanglaPdfService(font, newStyle, info);
    }

    public BanglaPdfService withTitle(String title) {
        return new BanglaPdfService(font, style, new DocumentInfo(title, info.author()));
    }

    public BanglaPdfService withAuthor(String author) {
        return new BanglaPdfService(font, style, new DocumentInfo(info.title(), author));
    }

    /**
     * Renders text and writes it to {@code destination}. Blank lines separate
     * paragraphs; single newlines are line breaks; everything else wraps to the
     * column width.
     *
     * @return the number of pages written
     */
    public int write(String text, Path destination) {
        return write(toParagraphs(text), destination);
    }

    /** Renders one paragraph per list element and writes the result. */
    public int write(List<String> paragraphs, Path destination) {
        Objects.requireNonNull(destination, "destination");
        Rendered rendered = render(paragraphs);
        try {
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(destination, rendered.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the PDF to " + destination, e);
        }
        return rendered.pageCount();
    }

    /** Renders text and returns the PDF bytes instead of writing a file. */
    public byte[] toBytes(String text) {
        return render(toParagraphs(text)).bytes();
    }

    /** Renders paragraphs and returns the PDF bytes instead of writing a file. */
    public byte[] toBytes(List<String> paragraphs) {
        return render(paragraphs).bytes();
    }

    /**
     * Renders a {@link Document} -- prose and tables, in order -- and writes it
     * to {@code destination}. Use this instead of {@link #write(String, Path)}
     * when the content needs a table: an itemised invoice, a price list,
     * anything with columns that need to actually line up, which plain
     * left-aligned paragraphs cannot express.
     *
     * @return the number of pages written
     */
    public int write(Document document, Path destination) {
        Objects.requireNonNull(destination, "destination");
        Rendered rendered = renderDocument(document);
        try {
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(destination, rendered.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the PDF to " + destination, e);
        }
        return rendered.pageCount();
    }

    /** Renders a {@link Document} and returns the PDF bytes instead of writing a file. */
    public byte[] toBytes(Document document) {
        return renderDocument(document).bytes();
    }

    /**
     * Reports characters the font has no glyph for. Worth calling before
     * shipping a document: a missing glyph is drawn as a blank or a box, and
     * nothing else in the pipeline will complain about it.
     *
     * @return the offending characters, in the order they first appear
     */
    public List<String> unsupportedCharacters(String text) {
        List<String> missing = new ArrayList<>();
        text.codePoints().distinct().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if (!Character.isWhitespace(codePoint) && font.shaper().shape(character).hasMissingGlyphs()) {
                missing.add(character);
            }
        });
        return List.copyOf(missing);
    }

    private Rendered render(List<String> paragraphs) {
        Objects.requireNonNull(paragraphs, "paragraphs");
        List<DocumentPart> parts = paragraphs.stream().<DocumentPart>map(DocumentPart.Paragraph::new).toList();
        return renderParts(parts);
    }

    private Rendered renderDocument(Document document) {
        Objects.requireNonNull(document, "document");
        return renderParts(document.parts());
    }

    private Rendered renderParts(List<DocumentPart> parts) {
        List<Page> pages = new TextLayout(font.shaper(), style).paginate(parts);
        byte[] bytes = new PdfDocumentBuilder(font.bytes(), font.metrics(), style, info).write(pages);
        return new Rendered(bytes, pages.size());
    }

    /** Splits on blank lines, the convention every plain-text format uses. */
    private static List<String> toParagraphs(String text) {
        Objects.requireNonNull(text, "text");
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.strip().split("\\R[ \t]*\\R")) {
            if (!block.isBlank()) {
                paragraphs.add(block.strip());
            }
        }
        return paragraphs.isEmpty() ? List.of("") : paragraphs;
    }

    private static byte[] readBundledFont() {
        try (InputStream in = BanglaPdfService.class.getResourceAsStream(BUNDLED_FONT)) {
            if (in == null) {
                throw new BanglaPdfException("The bundled font " + BUNDLED_FONT + " is not on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BanglaPdfException("Could not read the bundled font " + BUNDLED_FONT, e);
        }
    }

    @Override
    public void close() {
        font.close();
    }

    private record Rendered(byte[] bytes, int pageCount) {
    }

    /**
     * The font, parsed once and opened in HarfBuzz once, shared by every
     * service derived from the same {@code withFont} call.
     */
    private static final class LoadedFont implements AutoCloseable {

        private final byte[] bytes;
        private final TrueTypeFont metrics;
        private TextShaper shaper;
        private boolean closed;

        LoadedFont(byte[] bytes, String language) {
            this.bytes = bytes;
            this.metrics = new TrueTypeFont(bytes);
            this.shaper = new TextShaper(bytes, metrics.unitsPerEm, language);
        }

        TrueTypeFont metrics() {
            return metrics;
        }

        /** The original font bytes, handed to PDFBox for embedding on every render. */
        byte[] bytes() {
            return bytes;
        }

        TextShaper shaper() {
            if (closed) {
                throw new BanglaPdfException("This BanglaPdfService has already been closed");
            }
            return shaper;
        }

        void switchLanguage(String language) {
            shaper().close();
            shaper = new TextShaper(bytes, metrics.unitsPerEm, language);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                shaper.close();
            }
        }
    }
}
