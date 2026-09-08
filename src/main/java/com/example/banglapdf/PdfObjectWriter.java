package com.example.banglapdf;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Assembles a PDF file out of indirect objects: the header, the object bodies,
 * the cross-reference table that says where each of them starts, and the
 * trailer that points at the catalog.
 *
 * <p>Streams are Flate-compressed, which matters here more than usual -- the
 * whole Bangla font travels inside the file.
 */
final class PdfObjectWriter {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream(1 << 16);
    private final List<Integer> offsets = new ArrayList<>();

    PdfObjectWriter() {
        offsets.add(0); // object 0 is the head of the free list and is never written
    }

    /** Reserves an object number, so objects can refer to each other in any order. */
    int allocate() {
        offsets.add(-1);
        return offsets.size() - 1;
    }

    /** Writes an object whose body is a dictionary, e.g. {@code << /Type /Catalog >>}. */
    void writeDictionary(int objectNumber, String dictionary) {
        beginObject(objectNumber);
        write(dictionary);
        write("\nendobj\n");
    }

    /**
     * Writes a stream object: a dictionary describing the payload, then the
     * payload itself.
     *
     * @param extraEntries dictionary entries to add alongside the generated
     *                     {@code /Length} and {@code /Filter}, or {@code ""}
     */
    void writeStream(int objectNumber, String extraEntries, byte[] data) {
        byte[] payload = deflate(data);
        beginObject(objectNumber);
        write("<< " + (extraEntries.isBlank() ? "" : extraEntries.strip() + " ")
                + "/Filter /FlateDecode /Length " + payload.length + " >>\nstream\n");
        body.writeBytes(payload);
        write("\nendstream\nendobj\n");
    }

    /** Finishes the file and returns its bytes. */
    byte[] finish(int catalogObject, int infoObject) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 1024);
        // The binary comment on line 2 tells transfer tools this is not text.
        out.writeBytes(PdfSyntax.ascii("%PDF-1.7\n%âãÏÓ\n"));
        int bodyStart = out.size();
        out.writeBytes(body.toByteArray());

        int xrefOffset = out.size();
        int size = offsets.size();
        StringBuilder trailer = new StringBuilder(size * 20 + 256);
        trailer.append("xref\n0 ").append(size).append('\n');
        trailer.append("0000000000 65535 f \n");
        for (int i = 1; i < size; i++) {
            int offset = offsets.get(i);
            if (offset < 0) {
                throw new BanglaPdfException("Object " + i + " was allocated but never written");
            }
            trailer.append(String.format("%010d 00000 n \n", offset + bodyStart));
        }
        String id = fileId(out.toByteArray());
        trailer.append("trailer\n<< /Size ").append(size)
                .append(" /Root ").append(catalogObject).append(" 0 R")
                .append(" /Info ").append(infoObject).append(" 0 R")
                .append(" /ID [<").append(id).append("> <").append(id).append(">] >>\n")
                .append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
        out.writeBytes(PdfSyntax.ascii(trailer.toString()));
        return out.toByteArray();
    }

    private void beginObject(int objectNumber) {
        if (objectNumber <= 0 || objectNumber >= offsets.size()) {
            throw new BanglaPdfException("Object number " + objectNumber + " was never allocated");
        }
        if (offsets.get(objectNumber) >= 0) {
            throw new BanglaPdfException("Object " + objectNumber + " was written twice");
        }
        offsets.set(objectNumber, body.size());
        write(objectNumber + " 0 obj\n");
    }

    private void write(String text) {
        body.writeBytes(PdfSyntax.ascii(text));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** A stable per-file identifier, which readers and signing tools expect to find. */
    private static String fileId(byte[] fileSoFar) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(fileSoFar);
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BanglaPdfException("MD5 is unavailable in this JVM", e);
        }
    }
}
