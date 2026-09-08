package com.example.banglapdf;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The raw binding to {@code libharfbuzz}, done with the JDK's Foreign Function
 * &amp; Memory API -- no JNI glue, no third-party binding library. Only the
 * dozen entry points needed to turn a string into positioned glyph ids are
 * declared.
 *
 * <p>This class knows nothing about PDFs or Bangla; it is a thin, mechanical
 * translation of the C API. {@link TextShaper} supplies the meaning.
 */
final class HarfBuzzLibrary {

    /** {@code hb_memory_mode_t}: the blob's bytes outlive the blob and are never written. */
    static final int MEMORY_MODE_READONLY = 1;

    /** Layout of both {@code hb_glyph_info_t} and {@code hb_glyph_position_t}: five 32-bit fields. */
    static final int GLYPH_STRUCT_SIZE = 20;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = locate();

    private static final MethodHandle BLOB_CREATE =
            bind("hb_blob_create", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle BLOB_DESTROY =
            bind("hb_blob_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle FACE_CREATE =
            bind("hb_face_create", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle FACE_DESTROY =
            bind("hb_face_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle FONT_CREATE =
            bind("hb_font_create", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle FONT_DESTROY =
            bind("hb_font_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle FONT_SET_SCALE =
            bind("hb_font_set_scale", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle OT_FONT_SET_FUNCS =
            bind("hb_ot_font_set_funcs", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle BUFFER_CREATE =
            bind("hb_buffer_create", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle BUFFER_DESTROY =
            bind("hb_buffer_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle BUFFER_ADD_UTF8 =
            bind("hb_buffer_add_utf8", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle BUFFER_SET_LANGUAGE =
            bind("hb_buffer_set_language", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle BUFFER_GUESS_SEGMENT_PROPERTIES =
            bind("hb_buffer_guess_segment_properties", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle LANGUAGE_FROM_STRING =
            bind("hb_language_from_string", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SHAPE =
            bind("hb_shape", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle BUFFER_GET_GLYPH_INFOS =
            bind("hb_buffer_get_glyph_infos", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle BUFFER_GET_GLYPH_POSITIONS =
            bind("hb_buffer_get_glyph_positions", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private HarfBuzzLibrary() {
    }

    static MemorySegment blobCreate(MemorySegment data, int length, int mode) {
        return call(BLOB_CREATE, "hb_blob_create", data, length, mode, MemorySegment.NULL, MemorySegment.NULL);
    }

    static MemorySegment faceCreate(MemorySegment blob, int index) {
        return call(FACE_CREATE, "hb_face_create", blob, index);
    }

    static MemorySegment fontCreate(MemorySegment face) {
        return call(FONT_CREATE, "hb_font_create", face);
    }

    static MemorySegment bufferCreate() {
        return call(BUFFER_CREATE, "hb_buffer_create");
    }

    static MemorySegment languageFromString(MemorySegment utf8Tag) {
        return call(LANGUAGE_FROM_STRING, "hb_language_from_string", utf8Tag, -1);
    }

    static MemorySegment glyphInfos(MemorySegment buffer, MemorySegment countOut) {
        return call(BUFFER_GET_GLYPH_INFOS, "hb_buffer_get_glyph_infos", buffer, countOut);
    }

    static MemorySegment glyphPositions(MemorySegment buffer, MemorySegment countOut) {
        return call(BUFFER_GET_GLYPH_POSITIONS, "hb_buffer_get_glyph_positions", buffer, countOut);
    }

    static void fontSetScale(MemorySegment font, int xScale, int yScale) {
        callVoid(FONT_SET_SCALE, "hb_font_set_scale", font, xScale, yScale);
    }

    static void otFontSetFuncs(MemorySegment font) {
        callVoid(OT_FONT_SET_FUNCS, "hb_ot_font_set_funcs", font);
    }

    static void bufferAddUtf8(MemorySegment buffer, MemorySegment text, int textLength, int itemOffset, int itemLength) {
        callVoid(BUFFER_ADD_UTF8, "hb_buffer_add_utf8", buffer, text, textLength, itemOffset, itemLength);
    }

    static void bufferSetLanguage(MemorySegment buffer, MemorySegment language) {
        callVoid(BUFFER_SET_LANGUAGE, "hb_buffer_set_language", buffer, language);
    }

    static void bufferGuessSegmentProperties(MemorySegment buffer) {
        callVoid(BUFFER_GUESS_SEGMENT_PROPERTIES, "hb_buffer_guess_segment_properties", buffer);
    }

    static void shape(MemorySegment font, MemorySegment buffer) {
        callVoid(SHAPE, "hb_shape", font, buffer, MemorySegment.NULL, 0);
    }

    static void bufferDestroy(MemorySegment buffer) {
        callVoid(BUFFER_DESTROY, "hb_buffer_destroy", buffer);
    }

    static void fontDestroy(MemorySegment font) {
        callVoid(FONT_DESTROY, "hb_font_destroy", font);
    }

    static void faceDestroy(MemorySegment face) {
        callVoid(FACE_DESTROY, "hb_face_destroy", face);
    }

    static void blobDestroy(MemorySegment blob) {
        callVoid(BLOB_DESTROY, "hb_blob_destroy", blob);
    }

    private static MemorySegment call(MethodHandle handle, String name, Object... args) {
        try {
            return (MemorySegment) handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new BanglaPdfException("Call to " + name + " failed", t);
        }
    }

    private static void callVoid(MethodHandle handle, String name, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new BanglaPdfException("Call to " + name + " failed", t);
        }
    }

    private static MethodHandle bind(String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = LIB.find(symbol).orElseThrow(() -> new BanglaPdfException(
                "libharfbuzz is missing the symbol " + symbol + "; the library found is too old or is not HarfBuzz"));
        return LINKER.downcallHandle(address, descriptor);
    }

    /**
     * Finds the shared library. {@code HARFBUZZ_LIB} wins if set, then the usual
     * install locations, then the platform loader's own search path.
     */
    private static SymbolLookup locate() {
        String[] candidates = {
                System.getenv("HARFBUZZ_LIB"),
                "/opt/homebrew/lib/libharfbuzz.dylib",        // Homebrew, Apple Silicon
                "/usr/local/lib/libharfbuzz.dylib",           // Homebrew, Intel
                "/usr/lib/x86_64-linux-gnu/libharfbuzz.so.0", // Debian/Ubuntu
                "/usr/lib/aarch64-linux-gnu/libharfbuzz.so.0",
                "/usr/lib64/libharfbuzz.so.0",                // Fedora/RHEL
                "/usr/lib/libharfbuzz.so",
                "libharfbuzz.so.0",
                "libharfbuzz.so",
                "harfbuzz",
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(candidate);
                return Files.exists(path)
                        ? SymbolLookup.libraryLookup(path, Arena.global())
                        : SymbolLookup.libraryLookup(candidate, Arena.global());
            } catch (RuntimeException ignored) {
                // Not there, or not loadable on this architecture -- try the next one.
            }
        }
        throw new BanglaPdfException("""
                Could not load libharfbuzz, which does the Unicode shaping.
                Install it and try again:
                  macOS         brew install harfbuzz
                  Debian/Ubuntu sudo apt install libharfbuzz0b
                  Fedora/RHEL   sudo dnf install harfbuzz
                Or point HARFBUZZ_LIB at the shared library directly.""");
    }
}
