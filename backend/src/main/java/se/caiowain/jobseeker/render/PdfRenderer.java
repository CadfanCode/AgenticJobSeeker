package se.caiowain.jobseeker.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML to PDF through headless Chromium. The only component in the project that drives a
 * browser, and the reason the preview can be trusted: it prints the same string the preview
 * endpoint returns.
 *
 * <p>A Playwright instance is created per call rather than held open. Approving one
 * application is a deliberate, occasional act that already takes seconds, and a per-call
 * instance avoids owning a browser process's lifecycle for the life of the application.
 *
 * <p>A launch measured at roughly 4.6s on development hardware, so a caller rendering more
 * than one document — an approval renders a CV and a letter — should use {@link #renderAll}
 * rather than calling {@link #render} repeatedly: one launch for the whole batch instead of
 * one per document.
 */
@Component
public class PdfRenderer {

    /** One browser launch, several PDFs, and the version string captured from that same session. */
    public record RenderedDocuments(List<byte[]> pdfs, String rendererName) {
    }

    /** Renders a single document. A per-call browser launch; see {@link #renderAll} for more than one. */
    public byte[] render(String html) {
        return renderAll(List.of(html)).pdfs().get(0);
    }

    /** Renders every document in one browser session. One launch, not one per document. */
    public RenderedDocuments renderAll(List<String> htmls) {
        Playwright playwright = createPlaywright();
        try (playwright) {
            Browser browser = launch(playwright);
            try (browser) {
                List<byte[]> pdfs = new ArrayList<>(htmls.size());
                for (String html : htmls) {
                    pdfs.add(renderPdf(browser, html));
                }
                return new RenderedDocuments(List.copyOf(pdfs), "chromium/" + browser.version());
            } catch (Exception e) {
                throw renderingFailed(e);
            }
        }
    }

    /**
     * Whether Chromium is installed and can launch. This costs as much as a full render — it
     * launches and tears down a browser to find out — so it must not be put in a hot path or
     * a health check.
     */
    public boolean isAvailable() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            return browser.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /** Recorded on every archive row, so a future reader knows what produced the file. */
    public String rendererName() {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            return "chromium/" + browser.version();
        } catch (Exception e) {
            return "chromium/unavailable";
        }
    }

    private static byte[] renderPdf(Browser browser, String html) {
        Page page = browser.newPage();
        // No external resources by construction, so LOAD settles immediately.
        page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
        return page.pdf(new Page.PdfOptions()
                .setFormat("A4")
                .setPrintBackground(true)
                .setMargin(pageMargin()));
    }

    /**
     * Matches {@link DocumentHtmlBuilder}'s {@code @page} rule exactly. Verified empirically
     * (not assumed) that the two do not stack when both are present on this project's pinned
     * Chromium — passing the same values here is a safety net, not a duplicate margin.
     */
    private static Margin pageMargin() {
        return new Margin()
                .setTop(DocumentHtmlBuilder.MARGIN_VERTICAL)
                .setBottom(DocumentHtmlBuilder.MARGIN_VERTICAL)
                .setLeft(DocumentHtmlBuilder.MARGIN_HORIZONTAL)
                .setRight(DocumentHtmlBuilder.MARGIN_HORIZONTAL);
    }

    private static Playwright createPlaywright() {
        try {
            return Playwright.create();
        } catch (Exception e) {
            throw noBrowser(e);
        }
    }

    private static Browser launch(Playwright playwright) {
        try {
            return playwright.chromium().launch();
        } catch (Exception e) {
            throw noBrowser(e);
        }
    }

    /** No browser to launch with — the install step was never run, or found nothing. */
    private static RendererUnavailableException noBrowser(Exception e) {
        return new RendererUnavailableException(
                "No browser available to render. Install one with: ./mvnw exec:java "
                        + "-Dexec.mainClass=com.microsoft.playwright.CLI "
                        + "-Dexec.args=\"install chromium\" (" + e.getMessage() + ")", e);
    }

    /**
     * A browser exists, but rendering a specific document failed once it was underway — a
     * crash, an OOM, a future bug in {@link DocumentHtmlBuilder}. Distinct from {@link
     * #noBrowser}: re-running the install step would not help here, so the message must not
     * suggest it.
     */
    private static RendererUnavailableException renderingFailed(Exception e) {
        return new RendererUnavailableException("Rendering failed: " + e.getMessage(), e);
    }
}
