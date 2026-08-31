package se.caiowain.jobseeker.render;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentHtmlBuilderTest {

    private final DocumentHtmlBuilder builder = new DocumentHtmlBuilder();

    private CvContent cv(String... bullets) {
        return new CvContent("Cai Wain", "Senior Software Engineer", "cai@example.com",
                "+46 70 000 00 00", "Stockholm, Sweden", "Backend engineer.",
                List.of("Java", "Spring Boot"),
                List.of(new CvContent.Experience("Acme AB", "Backend Developer",
                        "2022", "2026", "Stockholm", List.of(bullets))));
    }

    @Test
    void producesAStandaloneDocument() {
        String html = builder.cvHtml(cv("Built REST APIs"));

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<style>");
        assertThat(html).contains("@page");
    }

    @Test
    void referencesNoExternalResource() {
        // A font or stylesheet fetched at render time makes the output non-deterministic and
        // breaks offline. It would also fail silently — the document simply looks different.
        String html = builder.cvHtml(cv("Built REST APIs"));

        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(html).doesNotContain("<link").doesNotContain("<script").doesNotContain("@import");
    }

    @Test
    void carriesBulletTextThrough() {
        String html = builder.cvHtml(cv("Built REST APIs in Java"));

        assertThat(html).contains("Built REST APIs in Java");
    }

    @Test
    void escapesMarkupSoAContentBulletCannotBreakTheDocument() {
        // A real CV says things like "R&D" and "latency < 50ms". Unescaped, they corrupt the
        // page; worse, a "<" could swallow the rest of the document silently.
        String html = builder.cvHtml(cv("R&D on <adaptive> latency < 50ms"));

        assertThat(html).contains("R&amp;D on &lt;adaptive&gt; latency &lt; 50ms");
        assertThat(html).doesNotContain("<adaptive>");
    }

    @Test
    void keepsSwedishCharacters() {
        String html = builder.cvHtml(cv("Ansvarade för plattformens tillgänglighet"));

        assertThat(html).contains("Ansvarade för plattformens tillgänglighet");
        assertThat(html).contains("charset=\"utf-8\"");
    }

    @Test
    void rendersBulletsInTheOrderGiven() {
        String html = builder.cvHtml(cv("First one", "Second one", "Third one"));

        assertThat(html.indexOf("First one")).isLessThan(html.indexOf("Second one"));
        assertThat(html.indexOf("Second one")).isLessThan(html.indexOf("Third one"));
    }

    @Test
    void theLetterCarriesTheProseAndPreservesItsParagraphs() {
        LetterContent letter = new LetterContent("Cai Wain", "cai@example.com", null,
                "Stockholm", "Example AB", "Plattformsingenjör",
                LocalDate.parse("2026-08-31"), "Hej,\n\nJag söker tjänsten.");

        String html = builder.letterHtml(letter);

        assertThat(html).contains("Example AB").contains("Plattformsingenjör");
        assertThat(html).contains("Hej,").contains("Jag söker tjänsten.");
        // Two paragraphs, not one run-on line.
        assertThat(html).contains("<p>");
    }

    @Test
    void anEmptyLetterBodyStillProducesADocument() {
        LetterContent letter = new LetterContent("Cai Wain", "cai@example.com", null,
                "Stockholm", "Example AB", "Utvecklare",
                LocalDate.parse("2026-08-31"), "");

        String html = builder.letterHtml(letter);

        assertThat(html).startsWith("<!DOCTYPE html>").contains("Cai Wain");
    }
}
