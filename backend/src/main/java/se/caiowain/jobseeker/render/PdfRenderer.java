package se.caiowain.jobseeker.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

/**
 * HTML to PDF through headless Chromium. The only component in the project that drives a
 * browser, and the reason the preview can be trusted: it prints the same string the preview
 * endpoint returns.
 *
 * <p>A Playwright instance is created per render rather than held open. Approving one
 * application is a deliberate, occasional act that already takes seconds, and a per-call
 * instance avoids owning a browser process's lifecycle for the life of the application.
 */
@Component
public class PdfRenderer {

    public byte[] render(String html) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {

            Page page = browser.newPage();
            // No external resources by construction, so LOAD settles immediately.
            page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
            return page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true));

        } catch (Exception e) {
            throw new RendererUnavailableException(
                    "Could not render the document. Install a browser with: ./mvnw exec:java "
                            + "-Dexec.mainClass=com.microsoft.playwright.CLI "
                            + "-Dexec.args=\"install chromium\" (" + e.getMessage() + ")", e);
        }
    }

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
}
