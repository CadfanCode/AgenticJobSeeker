package se.caiowain.jobseeker.render;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
                        "2022", "2026", false, "Stockholm", List.of(bullets))),
                List.of());
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Enough experiences and bullets to overflow onto a second page. */
    private CvContent longCv() {
        List<CvContent.Experience> experiences = new ArrayList<>();
        for (int job = 0; job < 8; job++) {
            List<String> bullets = new ArrayList<>();
            for (int b = 0; b < 8; b++) {
                bullets.add("Ansvarade för leverans av mjukvara i ett agilt team, uppgift "
                        + job + "." + b);
            }
            experiences.add(new CvContent.Experience("Employer " + job, "Role " + job,
                    "20" + job, "20" + (job + 1), false, "Stockholm", bullets));
        }
        return new CvContent("Cai Wain", "Senior Software Engineer", "cai@example.com",
                null, "Stockholm, Sweden", null, List.of("Java", "Spring", "PostgreSQL"),
                List.copyOf(experiences), List.of());
    }

    /** The smallest X and Y among the text on one page — how close the nearest text gets to the edge. */
    private static float[] minInset(PDDocument document, int pageNumber) throws Exception {
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                for (TextPosition position : textPositions) {
                    min[0] = Math.min(min[0], position.getX());
                    min[1] = Math.min(min[1], position.getY());
                }
            }
        };
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.getText(document);
        return min;
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

    @Test
    void marginsAreNotFlushToTheEdgeIncludingOnASecondPage() throws Exception {
        // The fidelity check @page margin alone cannot prove: PDFTextStripper's plain text
        // extraction sees the same bullets whether they print flush to the paper edge or not.
        // This subclasses the stripper to capture where the text actually sits on the page.
        byte[] pdf = renderer.render(builder.cvHtml(longCv()));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages())
                    .as("the fixture must actually overflow onto a second page to prove the "
                            + "margin holds across a page break, not just on the first page")
                    .isGreaterThanOrEqualTo(2);

            float[] page1 = minInset(document, 1);
            float[] page2 = minInset(document, 2);

            // A band, not typesetting precision, but narrow enough to actually back the class
            // comment's claim that the CSS @page margin and the explicit Page.PdfOptions
            // margin do not stack — a plain lower bound of 20f passed whether they stacked or
            // not and verified nothing about that claim. Measured empirically on this
            // project's pinned Chromium under today's single-margin code path: left inset
            // 45.75pt (~16mm, matching MARGIN_HORIZONTAL) on both pages; top inset 73.5pt on
            // page 1 (the ~22pt above the 51pt/18mm MARGIN_VERTICAL comes from the h1
            // heading's own line-height) and 63.75pt on page 2. If the two margins stacked,
            // the horizontal inset — nothing else pushes it around — would roughly double to
            // ~91.5pt, and the vertical insets would climb well past 100pt. The upper bounds
            // below sit strictly between the measured single-margin values and that
            // doubled/stacked estimate, so a future regression that starts stacking the two
            // margins fails this test rather than passing it by coincidence.
            assertThat(page1[0]).as("page 1 left inset (pt)").isBetween(20f, 80f);
            assertThat(page1[1]).as("page 1 top inset (pt)").isBetween(20f, 95f);
            assertThat(page2[0]).as("page 2 left inset (pt)").isBetween(20f, 80f);
            assertThat(page2[1]).as("page 2 top inset (pt)").isBetween(20f, 95f);
        }
    }

    @Test
    void renderAllRendersEveryDocumentInOneBrowserSessionWithMatchingRendererNames() throws Exception {
        String cvHtml = builder.cvHtml(cv("Built REST APIs in Java"));
        String letterHtml = builder.cvHtml(cv("Ansvarade för plattformens tillgänglighet i Göteborg"));

        PdfRenderer.RenderedDocuments rendered = renderer.renderAll(List.of(cvHtml, letterHtml));

        assertThat(rendered.pdfs()).hasSize(2);
        assertThat(rendered.rendererName()).isNotBlank().containsIgnoringCase("chromium");
        assertThat(textOf(rendered.pdfs().get(0))).contains("Built REST APIs in Java");
        assertThat(textOf(rendered.pdfs().get(1)))
                .contains("Ansvarade för plattformens tillgänglighet i Göteborg");
        // Every document in the batch came from the same session, so the recorded engine
        // matches what a standalone render() reports too.
        assertThat(rendered.rendererName()).isEqualTo(renderer.rendererName());
    }
}
