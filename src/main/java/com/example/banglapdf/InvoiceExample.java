package com.example.banglapdf;

import java.nio.file.Path;
import java.util.List;

import com.example.banglapdf.DocumentPart.Column;
import com.example.banglapdf.DocumentPart.Paragraph;
import com.example.banglapdf.DocumentPart.Table;

/**
 * A second demo, alongside {@link Main}: a real Bangla invoice with an
 * actual line-item table and grid lines -- built from nothing but
 * {@link BanglaPdfService}'s public {@link Document}/{@link DocumentPart} API.
 * It exists to show that a real document, tables included, comes out the same
 * way as a paragraph of sample sentences: correct conjuncts and reordering in
 * the company name and customer address, correct mixed-script line breaking
 * in the invoice number and email, and Bangla-numeral arithmetic that lines up
 * in its own right-aligned column.
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=com.example.banglapdf.InvoiceExample
 * mvn -q compile exec:java -Dexec.mainClass=com.example.banglapdf.InvoiceExample -Dexec.args="my-invoice.pdf"
 * }</pre>
 */
public final class InvoiceExample {

    public static void main(String[] args) {
        Path destination = Path.of(args.length > 0 ? args[0] : "invoice-output.pdf");
        PdfStyle style = PdfStyle.a4().withFontSize(12);

        // Item description | quantity | unit price | line total. The last
        // three are numbers, so they are right-aligned; widths are chosen to
        // add up to less than style.textWidth() (483.28pt at this size).
        Table items = new Table(
                List.of(
                        Column.left("বিবরণ", 205),
                        Column.right("পরিমাণ", 55),
                        Column.right("একক মূল্য", 105),
                        Column.right("মোট", 115)),
                List.of(
                        List.of("বাংলা ক্যালিগ্রাফি বই", "২", "৳৪৫০.০০", "৳৯০০.০০"),
                        List.of("হাতে তৈরি নোটবুক", "৫", "৳১২০.০০", "৳৬০০.০০"),
                        List.of("কালি ও কলম সেট", "৩", "৳২৫০.০০", "৳৭৫০.০০")));

        // A second, header-less table for the totals, right-anchored so its
        // amount column lines up directly under the items table's "মোট"
        // column: indent + 160 + 115 = 483.28's items-table width of 480.
        Table totals = new Table(
                List.of(Column.left("", 160), Column.right("", 115)),
                List.of(
                        List.of("সাবটোটাল", "৳২,২৫০.০০"),
                        List.of("ভ্যাট (১৫%)", "৳৩৩৭.৫০"),
                        List.of("সর্বমোট", "৳২,৫৮৭.৫০")),
                false, 205);

        Document invoice = Document.of(
                new Paragraph("নীল আকাশ ট্রেডার্স\n"
                        + "বাড়ি ১২, রোড ৫, ধানমন্ডি, ঢাকা-১২০৯\n"
                        + "ফোন: ০১৭১২৩৪৫৬৭৮ | ইমেইল: info@nilakashtraders.com"),

                new Paragraph("চালান নং: NAT-2026-0042\n"
                        + "তারিখ: ৯ সেপ্টেম্বর, ২০২৬\n"
                        + "পরিশোধের মেয়াদ: প্রাপ্তির ১৫ দিনের মধ্যে"),

                new Paragraph("ক্রেতার তথ্য:\n"
                        + "জনাব রহিম উদ্দিন\n"
                        + "বাড়ি ৭, রোড ২, উত্তরা, ঢাকা-১২৩০"),

                items,
                totals,

                new Paragraph("ধন্যবাদান্তে,\n"
                        + "নীল আকাশ ট্রেডার্স\n"
                        + "এই চালানটি ইলেকট্রনিকভাবে তৈরি করা হয়েছে এবং স্বাক্ষরের প্রয়োজন নেই।"));

        try (BanglaPdfService pdf = BanglaPdfService.withBundledFont()) {
            BanglaPdfService configured = pdf
                    .withStyle(style)
                    .withTitle("চালান — নীল আকাশ ট্রেডার্স")
                    .withAuthor("নীল আকাশ ট্রেডার্স");

            int pages = configured.write(invoice, destination);

            System.out.printf("Wrote %s (%d page%s)%n",
                    destination.toAbsolutePath(), pages, pages == 1 ? "" : "s");

            String allText = String.join("", items.rows().stream().flatMap(List::stream).toList())
                    + String.join("", totals.rows().stream().flatMap(List::stream).toList());
            List<String> missing = configured.unsupportedCharacters(allText);
            if (!missing.isEmpty()) {
                System.out.println("Warning: the font has no glyph for " + String.join(" ", missing));
            }
        }
    }
}
