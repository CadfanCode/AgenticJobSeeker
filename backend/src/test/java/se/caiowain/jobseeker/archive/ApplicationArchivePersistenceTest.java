package se.caiowain.jobseeker.archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApplicationArchivePersistenceTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationArchiveRepository archives;

    private ApplicationArchive minimal(String title, Instant approvedAt) {
        ApplicationArchive archive = new ApplicationArchive();
        archive.setJobTitle(title);
        archive.setEmployerName("Example AB");
        archive.setJobDescriptionText("Vi söker en utvecklare med erfarenhet av Java.");
        archive.setLetterText("Hej,\n\nJag söker tjänsten.");
        archive.setCvPdf("%PDF-cv".getBytes(StandardCharsets.UTF_8));
        archive.setCvPdfSha256("a".repeat(64));
        archive.setLetterPdf("%PDF-letter".getBytes(StandardCharsets.UTF_8));
        archive.setLetterPdfSha256("b".repeat(64));
        archive.setCoveragePercent(67);
        archive.setApprovedAt(approvedAt);
        return archive;
    }

    @Test
    void roundTripsWithoutAnyApplicationAttached() {
        // The archive must be readable on its own — that is the entire point of the table.
        archives.deleteAllInBatch();

        ApplicationArchive saved = archives.saveAndFlush(
                minimal("Backend Developer", Instant.parse("2026-08-31T09:00:00Z")));

        ApplicationArchive reloaded = archives.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getJobTitle()).isEqualTo("Backend Developer");
        assertThat(reloaded.getJobDescriptionText()).contains("erfarenhet av Java");
        assertThat(reloaded.getTailoredApplication()).isNull();
    }

    @Test
    void storesPdfBytesVerbatim() {
        archives.deleteAllInBatch();
        byte[] cv = "%PDF-1.7 pretend this is a real cv".getBytes(StandardCharsets.UTF_8);

        ApplicationArchive archive = minimal("Utvecklare", Instant.parse("2026-08-31T09:00:00Z"));
        archive.setCvPdf(cv);
        Long id = archives.saveAndFlush(archive).getId();

        assertThat(archives.findById(id).orElseThrow().getCvPdf()).isEqualTo(cv);
    }

    @Test
    void listsNewestFirst() {
        archives.deleteAllInBatch();
        archives.saveAndFlush(minimal("Older", Instant.parse("2026-08-01T09:00:00Z")));
        archives.saveAndFlush(minimal("Newer", Instant.parse("2026-08-30T09:00:00Z")));

        var page = archives.findAllByOrderByApprovedAtDesc(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ApplicationArchive::getJobTitle)
                .containsExactly("Newer", "Older");
    }
}
