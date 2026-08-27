package se.caiowain.jobseeker.profile.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfBoxTextExtractor implements PdfTextExtractor {

    /** Below this, the document is almost certainly a scan with no text layer. */
    private static final int MINIMUM_USEFUL_CHARACTERS = 20;

    @Override
    public String extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfTextExtractionException("Uploaded file is empty");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            String normalised = text == null ? "" : text.strip();

            if (normalised.length() < MINIMUM_USEFUL_CHARACTERS) {
                throw new PdfTextExtractionException(
                        "The PDF contains no extractable text — is it a scanned image?");
            }
            return normalised;
        } catch (PdfTextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfTextExtractionException("Could not read the PDF: " + e.getMessage(), e);
        }
    }
}
