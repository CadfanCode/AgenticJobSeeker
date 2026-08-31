package se.caiowain.jobseeker.render;

import java.util.List;

/**
 * The single source of layout. Pure: no Spring, no I/O.
 *
 * <p>The preview endpoint returns exactly this string and {@link PdfRenderer} prints exactly
 * this string. That shared call is what makes the preview faithful — a design with two
 * renderers could only agree by coincidence, and would disagree silently.
 *
 * <p>Nothing here is fetched at render time: fonts are a system stack and the CSS is inline.
 * An external resource would make the output depend on the network and change the document
 * without any error when it failed to load.
 */
public class DocumentHtmlBuilder {

    private static final String FONT_STACK = "Georgia, 'Times New Roman', Times, serif";

    /**
     * The printed margin, shared with {@link PdfRenderer} so the PDF and this CSS never
     * disagree. Verified empirically against this project's pinned Chromium: headless PDF
     * export honours {@code @page margin} directly, and when {@link PdfRenderer} also passes
     * the identical values through {@code Page.PdfOptions.setMargin}, the two do not stack —
     * whichever one Chromium actually applies, the result is the same margin, so correctness
     * does not depend on which mechanism a future Chromium build prefers.
     *
     * <p>{@code @page} is a print-only at-rule that browsers ignore on screen, so it does
     * nothing for the preview iframe. The {@code @media screen} rule below gives the preview
     * an equivalent padding so approving something resembles what prints.
     */
    static final String MARGIN_VERTICAL = "18mm";
    static final String MARGIN_HORIZONTAL = "16mm";

    public String cvHtml(CvContent cv) {
        StringBuilder body = new StringBuilder();

        body.append("<header><h1>").append(escape(cv.fullName())).append("</h1>");
        if (notBlank(cv.headline())) {
            body.append("<p class=\"headline\">").append(escape(cv.headline())).append("</p>");
        }
        body.append("<p class=\"contact\">")
                .append(joinEscaped(cv.email(), cv.phone(), cv.location()))
                .append("</p></header>");

        if (notBlank(cv.summary())) {
            body.append("<section><h2>Profile</h2><p>")
                    .append(escape(cv.summary())).append("</p></section>");
        }

        if (!cv.skills().isEmpty()) {
            body.append("<section><h2>Skills</h2><p class=\"skills\">");
            for (int i = 0; i < cv.skills().size(); i++) {
                if (i > 0) {
                    body.append(" &middot; ");
                }
                body.append(escape(cv.skills().get(i)));
            }
            body.append("</p></section>");
        }

        body.append("<section><h2>Experience</h2>");
        for (CvContent.Experience experience : cv.experiences()) {
            body.append("<article><h3>").append(escape(experience.title()))
                    .append(" &mdash; ").append(escape(experience.employer())).append("</h3>")
                    .append("<p class=\"dates\">")
                    .append(joinEscaped(dates(experience), experience.location()))
                    .append("</p><ul>");
            for (String bullet : experience.bullets()) {
                body.append("<li>").append(escape(bullet)).append("</li>");
            }
            body.append("</ul></article>");
        }
        body.append("</section>");

        return page("CV — " + safe(cv.fullName()), body.toString());
    }

    public String letterHtml(LetterContent letter) {
        StringBuilder body = new StringBuilder();

        body.append("<header><h1>").append(escape(letter.candidateName())).append("</h1>")
                .append("<p class=\"contact\">")
                .append(joinEscaped(letter.candidateEmail(), letter.candidatePhone(),
                        letter.candidateLocation()))
                .append("</p></header>");

        body.append("<p class=\"meta\">").append(escape(letter.date().toString()));
        if (notBlank(letter.employerName())) {
            body.append("<br>").append(escape(letter.employerName()));
        }
        if (notBlank(letter.jobTitle())) {
            body.append("<br>").append(escape(letter.jobTitle()));
        }
        body.append("</p>");

        body.append("<section class=\"body\">").append(paragraphs(letter.body())).append("</section>");
        body.append("<p class=\"signoff\">").append(escape(letter.candidateName())).append("</p>");

        return page("Cover letter — " + safe(letter.candidateName()), body.toString());
    }

    /** Blank-line separated text becomes paragraphs; a single newline becomes a break. */
    private static String paragraphs(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.strip().split("\\n\\s*\\n")) {
            out.append("<p>").append(escape(paragraph).replace("\n", "<br>")).append("</p>");
        }
        return out.toString();
    }

    private static String dates(CvContent.Experience experience) {
        if (!notBlank(experience.startDate()) && !notBlank(experience.endDate())) {
            return null;
        }
        return safe(experience.startDate()) + " – " + safe(experience.endDate());
    }

    private static String joinEscaped(String... parts) {
        List<String> present = java.util.Arrays.stream(parts)
                .filter(DocumentHtmlBuilder::notBlank)
                .map(DocumentHtmlBuilder::escape)
                .toList();
        return String.join(" &middot; ", present);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Content comes from a CV and a job ad. Both routinely contain &amp;, &lt; and quotes. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                <style>
                @page { size: A4; margin: %s %s; }
                * { box-sizing: border-box; }
                body { font-family: %s; font-size: 10.5pt; line-height: 1.45;
                       color: #111; margin: 0; }
                @media screen { body { padding: %s %s; } }
                h1 { font-size: 20pt; margin: 0 0 2mm; letter-spacing: 0.2px; }
                h2 { font-size: 9pt; text-transform: uppercase; letter-spacing: 1.2px;
                     color: #555; border-bottom: 0.4pt solid #bbb;
                     padding-bottom: 1.5mm; margin: 7mm 0 3mm; }
                h3 { font-size: 11pt; margin: 0 0 1mm; }
                p { margin: 0 0 2.5mm; }
                .headline { font-size: 11.5pt; color: #333; }
                .contact, .dates, .meta { font-size: 9.5pt; color: #555; }
                .skills { font-size: 10pt; }
                article { margin-bottom: 5mm; page-break-inside: avoid; }
                ul { margin: 1.5mm 0 0; padding-left: 5mm; }
                li { margin-bottom: 1.2mm; }
                .body p { margin-bottom: 3.5mm; }
                .signoff { margin-top: 8mm; }
                </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(escape(title), MARGIN_VERTICAL, MARGIN_HORIZONTAL, FONT_STACK,
                MARGIN_VERTICAL, MARGIN_HORIZONTAL, body);
    }
}
