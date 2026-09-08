# bangla-pdf

Renders correctly shaped Bangla (Bengali) text to PDF from Java, with **no PDF
library and no shaping library** — no iText, no PDFBox, no Apache FOP, and no
headless browser doing the typesetting behind your back.

The text comes out looking right, and it comes out selectable, searchable and
copyable. Those are two different problems, and this project solves both.

```java
try (BanglaPdfService pdf = BanglaPdfService.withBundledFont()) {
    pdf.write("আমি বাংলায় গান গাই।", Path.of("out.pdf"));
}
```

---

## Contents

- [Quick start](#quick-start)
- [Using the service](#using-the-service)
- [Why Bangla is hard](#why-bangla-is-hard)
- [How it renders perfect Bangla](#how-it-renders-perfect-bangla)
- [Why the viewer cannot get it wrong](#why-the-viewer-cannot-get-it-wrong)
- [Why the text is still real text](#why-the-text-is-still-real-text)
- [Positioning marks](#positioning-marks)
- [Layout](#layout)
- [Project layout](#project-layout)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)

---

## Quick start

**Requirements**

| | |
|---|---|
| JDK 22 or newer | The Foreign Function &amp; Memory API (`java.lang.foreign`) is final as of 22 |
| libharfbuzz | `brew install harfbuzz` · `sudo apt install libharfbuzz0b` · `sudo dnf install harfbuzz` |

```bash
mvn compile exec:exec              # writes bangla-output.pdf
mvn compile exec:exec -Dexec.args="report.pdf"

mvn package                        # or build a runnable jar
java -jar target/bangla-pdf.jar report.pdf
```

`mvn exec:java` also works, but runs inside Maven's own JVM and cannot be given
`--enable-native-access`, so it prints a warning. `exec:exec` forks a clean JVM.

A Bangla font (Noto Sans Bengali) is bundled on the classpath, so there is
nothing else to install.

## Using the service

`BanglaPdfService` is the whole API. Everything else in the package is
package-private: the shaper, the font parser, the layout engine and the PDF
writer are not part of the surface you program against.

```java
try (BanglaPdfService pdf = BanglaPdfService.withBundledFont()) {

    // Blank lines separate paragraphs, single newlines are hard breaks,
    // and everything else wraps to the column.
    pdf.write(articleText, Path.of("article.pdf"));

    // Or hand it paragraphs directly.
    pdf.write(List.of("প্রথম অনুচ্ছেদ।", "দ্বিতীয় অনুচ্ছেদ।"), Path.of("two.pdf"));

    // Style and metadata are immutable and chainable.
    pdf.withStyle(PdfStyle.a4().withFontSize(15).withLineHeight(1.8f))
       .withTitle("বার্ষিক প্রতিবেদন")
       .withAuthor("দপ্তর")
       .write(reportText, Path.of("report.pdf"));

    // For an HTTP response or a blob store, skip the file entirely.
    byte[] bytes = pdf.toBytes(articleText);
}
```

`write` returns the page count. A different font is one call away —
`BanglaPdfService.withFont(Path.of("MyBangla.ttf"))` — as long as it has
TrueType (`glyf`) outlines.

**Check your font actually covers your text.** A missing glyph is drawn as a
blank box and nothing else in the pipeline will complain:

```java
List<String> missing = pdf.unsupportedCharacters(text);   // e.g. [☃]
```

`PdfStyle` is an immutable record; `a4()` gives A4 with 56pt margins, 14pt type
and 1.7× leading. That leading is deliberately looser than you would use for
Latin text: Bangla stacks vowel signs above the headline and hangs conjuncts
well below the baseline, so lines set at Latin leading collide.

---

## Why Bangla is hard

Bangla is an *abugida*, and almost nothing about it is one-character-one-glyph.
Three things go wrong the moment you assume otherwise. These are real numbers,
read back out of the PDF this project generates:

| You type | Code points | What is drawn |
|---|---|---|
| ক্ষ | `U+0995 U+09CD U+09B7` | **one** glyph, `0x0081` |
| জ্ঞ | `U+099C U+09CD U+099E` | **one** glyph, `0x0082` |
| ন্ত্র | `U+09A8 U+09CD U+09A4 U+09CD U+09B0` | **one** glyph, `0x0151` |
| কি | `U+0995 U+09BF` | two glyphs, drawn `0x0037` **then** `0x0014` |
| কর্ম | `U+0995 U+09B0 U+09CD U+09AE` | three glyphs, `0x0014 0x002C 0x0083` |

**1. Consonants fuse.** ক + ্ + ষ is not three letters side by side, it is the
single conjunct ক্ষ. There are hundreds of these, they are font-specific, and
there is no algorithm for them outside the font's own tables.

**2. Vowel signs move.** ি and ে are typed *after* their consonant and drawn
*before* it. In কি, the glyph the reader sees first (`0x0037`, the ি) is the
second character you typed. And ো and ৌ are drawn on *both* sides at once.

**3. Reph goes to the end.** In কর্ম the র is the second character typed, but it
becomes the little hook drawn last, over the ম — glyph `0x0083`, third in the
output.

Draw the code points in the order they were typed and you get কি as "িক" and
ক্ষ as three separate letters with a visible hasant between them. It is not
subtly wrong, it is unreadable.

## How it renders perfect Bangla

The rule the whole design follows: **decide everything before the PDF is
written, and leave the viewer nothing to interpret.**

```
  "কর্ম"
     │
     │  1. split into script-homogeneous runs
     ▼
  [Bangla run]
     │
     │  2. HarfBuzz applies the font's GSUB/GPOS tables
     ▼
  glyph 0x0014  advance 807   ← ক
  glyph 0x002C  advance 622   ← ম, now second
  glyph 0x0083  advance   0   ← reph, now last, zero width
     │
     │  3. measure, wrap to the column, flow onto pages
     ▼
  4. content stream: [<0014002C0083>] TJ
     │
     │  5. embed the font whole, add ToUnicode + ActualText
     ▼
  a PDF that draws exactly these glyphs and knows they spell "কর্ম"
```

**1 — Split by script.** `TextShaper` cuts the string into maximal runs of a
single Unicode script, so Bangla and Latin in one sentence are each shaped by
the rules that apply to them. Punctuation, digits and combining marks attach to
the run they follow instead of breaking it. The *whole* string is still passed
to HarfBuzz with the run marked as a range, so shaping at a run boundary can
see its neighbours.

**2 — Shape with HarfBuzz.** This is the step that makes it correct, and it is
not optional. HarfBuzz is the reference implementation of OpenType shaping — the
same engine inside Chrome, Firefox, Android and LibreOffice. It runs the font's
own `GSUB` table (substitution: ক+্+ষ → ক্ষ) and `GPOS` table (positioning:
where exactly the ু hangs under its consonant), applies the Indic reordering
rules, and hands back a list of glyph ids with advances and offsets in font
units. It is called directly as a native library through Java's Foreign Function
&amp; Memory API — no JNI shim, no wrapper dependency. See `HarfBuzzLibrary`.

Nothing else in this project knows anything about Bangla. It does not need to:
after this step there is no Bangla left, only positioned glyph indices.

**3 — Lay out on shaped widths.** `TextLayout` breaks lines by measuring shaped
runs, never by counting characters — the two have almost nothing to do with each
other when three code points can be one glyph and two can be two glyphs in the
opposite order. A word too long for the column is broken at *cluster*
boundaries, so a cut never lands inside ক্ষ and leaves a dangling hasant.

**4 — Emit glyph ids.** `ContentStreamBuilder` writes each glyph as its raw
two-byte glyph id.

**5 — Embed and annotate.** `PdfDocumentWriter` embeds the font byte-for-byte
and attaches the tables that let the text be read back as text.

## Why the viewer cannot get it wrong

Steps 4 and 5 rest on three PDF choices that stack into a single guarantee:

| Choice | Effect |
|---|---|
| `/Subtype /Type0` with `/Encoding /Identity-H` | Codes in the content stream are two-byte CIDs, not characters |
| `/CIDToGIDMap /Identity` | A CID *is* a glyph index in the embedded font |
| `/FontFile2` — the whole font, unmodified | The viewer has the exact outlines HarfBuzz chose from |

Together: **the bytes in the content stream are glyph indices into an embedded
font.** There is no character-to-glyph step left for the viewer to perform, and
therefore no step it can perform differently from the shaper — on a different
platform, with a different font substituted, or on a reader that has never heard
of Bangla. The typography was decided at generation time and is baked in.

This is also why no browser or WebView is needed. The usual "render HTML to PDF"
approach outsources shaping to a browser at runtime; here shaping happens once,
in-process, and the result is frozen into the file.

## Why the text is still real text

Identity-H buys correctness at a price: a naive Identity-H PDF is a *picture of
words*. Select it, copy it, search it, run a screen reader over it — nothing.
The codes in the file are glyph indices private to one font, and mean nothing to
anyone else.

Two tables fix that, and they are layered because each covers what the other
cannot.

**`ToUnicode`, built from the font's own cmap.** This is the per-glyph
dictionary. The obvious way to build it — "give each glyph the text of whichever
cluster used it first" — is subtly broken: the ক glyph first seen inside কা would
be recorded as "কা", and every bare ক in the document would then copy as "কা".
So the map is built by reading the font's own character map *backwards*, which
gives every glyph that exists because a character was typed its own unambiguous
answer. Only glyphs that exist purely as the output of a substitution — the
conjuncts, which no single character maps to — fall back to the shaper's cluster
text. See `TrueTypeFont.unicodeForGlyph`.

**`ActualText` spans, for order.** A per-glyph dictionary still cannot express
কি: the glyphs are drawn ি-then-ক, and reading them off in visual order gives
"িক". So any word whose glyphs do not map one-to-one onto its characters — which
in Bangla is most words — is wrapped in a marked-content span carrying the
original characters in their original order:

```
/Span << /ActualText <FEFF09AF09C1099509CD09A409BE099509CD09B709B0003A> >> BDC
[<002D003900FF00360081002E0243>] TJ
EMC
```

That is the word যুক্তাক্ষর: — ten code points on the `ActualText` line, seven
glyphs on the `TJ` line, in a different order, with ক্ষ collapsed into the single
glyph `0x0081`. The drawing instruction and the meaning are carried separately,
and neither has to compromise for the other.

The span covers a whole word rather than each cluster. It is the same claim
either way, but keeping it whole means no extractor has to decide what a gap
between two adjacent spans means — and the sub-pixel backtracking that positions
a Bangla mark reads to some of them as a word break when it lands exactly on a
span boundary.

The result round-trips. Extracting the demo page with `pdftotext` returns the
source string character for character, conjuncts and reordered matras included:

```bash
mvn compile exec:exec
pdftotext -enc UTF-8 bangla-output.pdf -
```

## Positioning marks

HarfBuzz returns each glyph with an advance and an (x, y) offset. The offsets
are what put the ু under its consonant and the ঁ over it, and they are applied
without ever moving the text pen:

- **Horizontal offsets fold into `TJ`.** A number inside a `TJ` array shifts the
  pen by −n/1000 of the font size, so a whole line of glyphs plus all their
  horizontal nudges is usually a single operator. Adjustments below half a
  thousandth of an em are dropped as invisible.
- **Vertical offsets become text rise (`Ts`).** `Ts` raises the glyph without
  disturbing the pen, which is exactly the semantics a stacked Bangla mark
  wants — no save/restore, no matrix arithmetic to drift.
- **Each line starts with an absolute `Tm`.** No pen state carries across lines,
  so rounding cannot accumulate down the page.

The arithmetic is done against the *rounded* width the PDF itself declares for
each glyph, not the raw font value, so the file's own numbers agree exactly and
nothing drifts along a line.

## Layout

- Blank lines separate paragraphs; single newlines are hard breaks.
- Greedy first-fit wrapping on shaped widths, with cluster-safe breaking for
  words wider than the column.
- Automatic pagination.
- `/W` declares a width for every glyph the document draws and no others — 111
  entries for the demo page, not the font's full glyph set.
- All streams are Flate-compressed. The demo is an 82 KB PDF carrying a 164 KB font.

## Project layout

```
BanglaPdfService     the entire public API; everything below is package-private
PdfStyle             page geometry and typography, immutable
BanglaPdfException   the one failure type callers see

TextShaper           script segmentation, HarfBuzz shaping, cluster mapping
HarfBuzzLibrary      raw FFM binding to libharfbuzz
TrueTypeFont         head/maxp/hhea/hmtx/post/name/cmap; never touches outlines
TextLayout           line breaking and pagination on shaped widths
ContentStreamBuilder glyphs to PDF operators: TJ, Ts, ActualText spans
GlyphUsage           which glyphs are drawn, and what text they stand for
ToUnicodeCMap        the glyph-to-Unicode table
PdfDocumentWriter    catalog, page tree, Type0/CIDFontType2 font, metadata
PdfObjectWriter      indirect objects, Flate streams, xref, trailer
PdfSyntax            PDF primitive value formatting
Main                 demo
```

## Limitations

- **No subsetting.** The whole font is embedded. Correct and simple, but a
  larger file than it needs to be — compression takes most of the sting out.
- **TrueType outlines only.** CFF/`OTTO` fonts would need `FontFile3`; the
  parser detects them and says so rather than producing a broken file.
- **No bidi reordering.** Runs are placed left to right. Mixed Bangla and Latin
  is fine; mixing in a right-to-left script is not.
- **Left-aligned only.** No justification, centring, tables or images.
- **One font per document.** No automatic fallback for characters the font
  lacks — `unsupportedCharacters` tells you about them instead.
- **Not thread-safe.** One service per thread; they are cheap.

## Troubleshooting

**`Could not load libharfbuzz`** — install it (see [Quick start](#quick-start)),
or set `HARFBUZZ_LIB` to the full path of the shared library.

**`java.lang.foreign` does not resolve** — you are on a JDK older than 22. Point
`JAVA_HOME` at 22+.

**Warning about restricted native access** — you ran `exec:java`. Use
`mvn compile exec:exec`, or add `--enable-native-access=ALL-UNNAMED` yourself.

**Conjuncts come out as separate letters with visible hasants** — the font has
no Bangla `GSUB` table. Check you are not passing a Latin-only font to
`withFont`.

**Boxes or blanks in the output** — the font has no glyph for those characters.
Run `unsupportedCharacters` to find out which.
