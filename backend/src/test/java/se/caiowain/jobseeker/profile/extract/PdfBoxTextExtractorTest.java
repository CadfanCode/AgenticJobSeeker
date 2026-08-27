package se.caiowain.jobseeker.profile.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBoxTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfBoxTextExtractor();

    /** Builds a small PDF in memory so the test needs no committed binary. */
    static byte[] pdfWithLines(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(16f);
                cs.newLineAtOffset(50, 750);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] emptyPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsTextFromAPdf() throws Exception {
        byte[] pdf = pdfWithLines(List.of("Cai Wain", "Senior Engineer at Acme AB", "2019 - present"));

        String text = extractor.extract(pdf);

        assertThat(text).contains("Cai Wain");
        assertThat(text).contains("Senior Engineer at Acme AB");
        assertThat(text).contains("2019 - present");
    }

    @Test
    void preservesLineStructure() throws Exception {
        // Lines must be long enough to clear the scanned-PDF heuristic; the behaviour
        // under test is line preservation, not the threshold.
        String text = extractor.extract(pdfWithLines(
                List.of("Experience: Senior Engineer", "Education: MSc Computer Science")));

        assertThat(text.lines().map(String::strip).toList())
                .contains("Experience: Senior Engineer", "Education: MSc Computer Science");
    }

    @Test
    void rejectsAPdfWithNoTextLayer() throws Exception {
        assertThatThrownBy(() -> extractor.extract(emptyPdf()))
                .isInstanceOf(PdfTextExtractionException.class)
                .hasMessageContaining("no extractable text");
    }

    @Test
    void rejectsBytesThatAreNotAPdf() {
        assertThatThrownBy(() -> extractor.extract("this is not a pdf".getBytes()))
                .isInstanceOf(PdfTextExtractionException.class);
    }
}
