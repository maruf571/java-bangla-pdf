package com.example.banglapdf;

import java.util.List;

/**
 * An ordered sequence of {@link DocumentPart}s -- prose and tables -- laid out
 * and paginated together, top to bottom, exactly in the order given.
 *
 * <pre>{@code
 * Document invoice = Document.of(
 *         new DocumentPart.Paragraph("নীল আকাশ ট্রেডার্স"),
 *         new DocumentPart.Table(columns, rows),
 *         new DocumentPart.Paragraph("ধন্যবাদান্তে,"));
 *
 * pdf.write(invoice, Path.of("invoice.pdf"));
 * }</pre>
 */
public record Document(List<DocumentPart> parts) {

    public Document {
        parts = List.copyOf(parts);
    }

    public static Document of(DocumentPart... parts) {
        return new Document(List.of(parts));
    }
}
