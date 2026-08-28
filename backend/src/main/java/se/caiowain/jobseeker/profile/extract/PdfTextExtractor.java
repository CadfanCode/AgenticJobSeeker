package se.caiowain.jobseeker.profile.extract;

/**
 * Turns PDF bytes into plain text.
 *
 * <p>An interface rather than a class so Slice 2b can accept other input formats, and so
 * tests can substitute a deterministic stand-in without constructing PDFs.
 */
public interface PdfTextExtractor {
    String extract(byte[] pdfBytes);
}
