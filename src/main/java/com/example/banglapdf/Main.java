package com.example.banglapdf;

import java.nio.file.Path;
import java.util.List;

/**
 * Demo for {@link BanglaPdfService}: writes a page of Bangla that exercises the
 * parts of the script a naive renderer gets wrong.
 *
 * <pre>{@code
 * mvn -q compile exec:java              # writes bangla-output.pdf
 * mvn -q compile exec:java -Dexec.args="my.pdf"
 * }</pre>
 */
public final class Main {

    private static final List<String> SAMPLE = List.of(
            "আমি বাংলায় গান গাই।",

            "বাংলাদেশ আমার প্রিয় জন্মভূমি। এই দেশের নদী, মাঠ, আকাশ আর মানুষের ভালোবাসা "
                    + "আমাকে প্রতিদিন নতুন করে বাঁচতে শেখায়। ভাষার জন্য প্রাণ দেওয়ার ইতিহাস "
                    + "পৃথিবীতে আর কোথাও নেই।",

            "যুক্তাক্ষর: ক্ষ, জ্ঞ, ন্ত্র, ত্ত্ব, স্ত্রী, উদ্ধৃত, বিদ্যুৎ, র‍‍্যাব।",

            "রেফ ও ফলা: কর্ম, ধর্ম, সূর্য, প্রশ্ন, ক্রম, হ্রদ, বিদ্রূপ।",

            "স্বরচিহ্নের পুনর্বিন্যাস: কি কী কু কূ কে কৈ কো কৌ কং কঃ কাঁ।",

            "সংখ্যা ও যতিচিহ্ন: ১২৩৪৫৬৭৮৯০ — “উদ্ধৃতি”, ‘একক’; ৫০% ও ৳১,২৩৪।",

            "Mixed script: Bangla ও English একসাথে, HarfBuzz 11.0, ২০২৬ সাল।");

    public static void main(String[] args) {
        Path destination = Path.of(args.length > 0 ? args[0] : "bangla-output.pdf");

        try (BanglaPdfService pdf = BanglaPdfService.withBundledFont()) {
            BanglaPdfService configured = pdf
                    .withStyle(PdfStyle.a4().withFontSize(15))
                    .withTitle("বাংলা পিডিএফ")
                    .withAuthor("bangla-pdf");

            int pages = configured.write(SAMPLE, destination);

            System.out.printf("Wrote %s (%d page%s)%n",
                    destination.toAbsolutePath(), pages, pages == 1 ? "" : "s");

            List<String> missing = configured.unsupportedCharacters(String.join("", SAMPLE));
            if (!missing.isEmpty()) {
                System.out.println("Warning: the font has no glyph for " + String.join(" ", missing));
            }
        }
    }
}
