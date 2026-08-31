package se.caiowain.jobseeker.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads the rendered PDF back with PDFBox — already a dependency from Slice 2a — so these
 * tests prove the file says what the screen said, rather than proving a file was produced.
 *
 * <p>Skipped when no browser is installed. A Playwright browser download is network access,
 * which the suite forbids, so Chromium is a developer prerequisite exactly as Ollama is.
 */
class PdfRendererTest {

    private final PdfRenderer renderer = new PdfRenderer();
    private final DocumentHtmlBuilder builder = new DocumentHtmlBuilder();

    @BeforeEach
    void requireABrowser() {
        assumeTrue(renderer.isAvailable(),
                "No Chromium installed — run the Playwright install step to exercise this test");
    }

    private CvContent cv(String... bullets) {
        return new CvContent("Cai Wain", "Senior Software Engineer", "cai@example.com",
                null, "Stockholm, Sweden", null, List.of("Java"),
                List.of(new CvContent.Experience("Acme AB", "Backend Developer",
                        "2022", "2026", "Stockholm", List.of(bullets))));
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void producesSomethingPdfBoxCanOpen() throws Exception {
        byte[] pdf = renderer.render(builder.cvHtml(cv("Built REST APIs in Java")));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF-");
    }

    @Test
    void theBulletsSurviveIntoTheFile() throws Exception {
        // The fidelity check: what the screen showed must be extractable from the artifact.
        byte[] pdf = renderer.render(builder.cvHtml(cv("Built REST APIs in Java")));

        assertThat(textOf(pdf)).contains("Built REST APIs in Java").contains("Cai Wain");
    }

    @Test
    void swedishCharactersSurviveTheRoundTrip() throws Exception {
        // Silent font-embedding failure is the classic PDF defect, and Slice 2b named
        // embedded fonts for åäö as a requirement.
        byte[] pdf = renderer.render(
                builder.cvHtml(cv("Ansvarade för plattformens tillgänglighet i Göteborg")));

        assertThat(textOf(pdf)).contains("Ansvarade för plattformens tillgänglighet i Göteborg");
    }

    @Test
    void namesTheEngineThatProducedTheFile() {
        assertThat(renderer.rendererName()).isNotBlank().containsIgnoringCase("chromium");
    }
}
