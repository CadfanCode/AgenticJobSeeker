package se.caiowain.jobseeker.fit;

import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.CvSkill;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Minimal valid rows for the fit tests. Every NOT NULL column gets a value. */
public final class FitFixtures {

    private FitFixtures() {
    }

    public static CvDocument document(String sha) {
        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(4);
        doc.setSha256(sha);
        doc.setContent("%PDF".getBytes(StandardCharsets.UTF_8));
        doc.setUploadedAt(Instant.parse("2026-08-30T09:00:00Z"));
        return doc;
    }

    /** A READY profile carrying the given skill names, in the order supplied. */
    public static CvProfile readyProfile(CvDocument document, String... skillNames) {
        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setFullName("Test Candidate");
        profile.setStatus(ProfileStatus.READY);
        profile.setExtractedAt(Instant.parse("2026-08-30T09:05:00Z"));

        int ordinal = 0;
        for (String name : skillNames) {
            CvSkill skill = new CvSkill();
            skill.setName(name);
            skill.setOrdinal(ordinal++);
            profile.addSkill(skill);
        }
        return profile;
    }

    /** Adds one experience carrying the given bullet texts. */
    public static void withBullets(CvProfile profile, String... bulletTexts) {
        CvExperience experience = new CvExperience();
        experience.setEmployer("Acme AB");
        experience.setTitle("Backend Developer");
        experience.setStartDate("2022");
        experience.setEndDate("2026");
        experience.setOrdinal(0);

        int ordinal = 0;
        for (String text : bulletTexts) {
            CvExperienceBullet bullet = new CvExperienceBullet();
            bullet.setText(text);
            bullet.setOrdinal(ordinal++);
            experience.addBullet(bullet);
        }
        profile.addExperience(experience);
    }

    public static JobPosting posting(String slug, String title, String description,
                                     String municipality) {
        JobPosting job = new JobPosting();
        job.setFingerprint(slug);
        job.setCanonicalUrl("https://example.test/jobs/" + slug);
        job.setTitle(title);
        job.setEmployerName("Example AB");
        job.setDescription(description);
        job.setMunicipality(municipality);
        job.setLanguage("sv");
        job.setFirstSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setLastSeenAt(Instant.parse("2026-08-30T08:00:00Z"));
        job.setPublishedAt(Instant.parse("2026-08-29T08:00:00Z"));
        return job;
    }
}
