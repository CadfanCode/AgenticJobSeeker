package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.*;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TailoredApplicationPersistenceTest extends AbstractIntegrationTest {

    @Autowired TailoredApplicationRepository applications;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    private JobPosting job;
    private CvProfile profile;

    @BeforeEach
    void seed() {
        applications.deleteAll();

        JobPosting j = new JobPosting();
        j.setFingerprint("fp-tailor-" + System.nanoTime());
        j.setCanonicalUrl("https://example.se/job");
        j.setTitle("Partner Engineer");
        j.setEmployerName("Academic Work");
        j.setDescription("Har erfarenhet av webbutveckling.");
        j.setAtsVendor(AtsVendor.OTHER);
        j.setStatus(JobStatus.DISCOVERED);
        j.setFirstSeenAt(Instant.now());
        j.setLastSeenAt(Instant.now());
        job = jobs.save(j);

        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1L);
        doc.setSha256("sha-tailor-" + System.nanoTime());
        doc.setContent(new byte[]{1});
        doc.setExtractedText("Built REST APIs in Java and Spring Boot.");
        doc.setUploadedAt(Instant.now());
        documents.save(doc);

        CvProfile p = new CvProfile();
        p.setCvDocument(doc);
        p.setFullName("Cai Wain");
        p.setStatus(ProfileStatus.READY);
        profile = profiles.save(p);
    }

    @Test
    void persistsApplicationWithRequirementsAndEvidence() {
        TailoredApplication app = new TailoredApplication();
        app.setJobPosting(job);
        app.setCvProfile(profile);
        app.setStatus(ApplicationStatus.DRAFT);
        app.setModelUsed("qwen2.5:7b-instruct");
        app.setCoveragePercent(50);
        app.setGeneratedAt(Instant.now());

        ApplicationRequirement req = new ApplicationRequirement();
        req.setText("Har erfarenhet av webbutveckling.");
        req.setOrdinal(0);
        req.setOverBroad(false);
        app.addRequirement(req);

        ApplicationEvidence ev = new ApplicationEvidence();
        ev.setBulletText("Built REST APIs in Java and Spring Boot.");
        ev.setOrdinal(0);
        req.addEvidence(ev);

        applications.save(app);

        TailoredApplication loaded =
                applications.findByJobPostingIdAndCvProfileId(job.getId(), profile.getId()).orElseThrow();
        assertThat(loaded.getRequirements()).hasSize(1);
        assertThat(loaded.getRequirements().getFirst().getEvidence()).hasSize(1);
        assertThat(loaded.getRequirements().getFirst().getEvidence().getFirst().getBulletText())
                .isEqualTo("Built REST APIs in Java and Spring Boot.");
        assertThat(loaded.getCoveragePercent()).isEqualTo(50);
    }

    @Test
    void evidenceSurvivesWithoutABulletReference() {
        TailoredApplication app = new TailoredApplication();
        app.setJobPosting(job);
        app.setCvProfile(profile);
        app.setStatus(ApplicationStatus.APPROVED);
        app.setGeneratedAt(Instant.now());

        ApplicationRequirement req = new ApplicationRequirement();
        req.setText("Har erfarenhet av webbutveckling.");
        req.setOrdinal(0);
        app.addRequirement(req);

        ApplicationEvidence ev = new ApplicationEvidence();
        ev.setCvExperienceBulletId(null);
        ev.setBulletText("Snapshot text that outlives its bullet");
        ev.setOrdinal(0);
        req.addEvidence(ev);

        applications.save(app);

        assertThat(applications.findByJobPostingIdAndCvProfileId(job.getId(), profile.getId())
                .orElseThrow().getRequirements().getFirst().getEvidence().getFirst().getBulletText())
                .isEqualTo("Snapshot text that outlives its bullet");
    }
}
