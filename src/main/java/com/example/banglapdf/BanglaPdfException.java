package com.example.banglapdf;

/**
 * Thrown when a document cannot be shaped or written. Everything that can go
 * wrong inside the renderer -- a missing native HarfBuzz library, an
 * unparseable font, an I/O failure -- surfaces as this single type, so callers
 * never have to know which layer failed.
 */
public class BanglaPdfException extends RuntimeException {

    public BanglaPdfException(String message) {
        super(message);
    }

    public BanglaPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
