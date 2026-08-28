package se.caiowain.jobseeker.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CvProfilePersistenceTest extends AbstractIntegrationTest {

    @Autowired CvDocumentRepository documents;
    @Autowired CvProfileRepository profiles;

    private CvDocument document(String sha) {
        CvDocument doc = new CvDocument();
        doc.setFilename("cv.pdf");
        doc.setContentType("application/pdf");
        doc.setSizeBytes(1234L);
        doc.setSha256(sha);
        doc.setContent(new byte[]{1, 2, 3});
        doc.setExtractedText("Acme AB Senior Engineer 2019");
        doc.setUploadedAt(Instant.now());
        return documents.save(doc);
    }

    @Test
    void persistsProfileWithExperienceBulletsEducationAndSkills() {
        CvProfile profile = new CvProfile();
        profile.setCvDocument(document("sha-a"));
        profile.setFullName("Cai");
        profile.setLanguage("en");
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profile.setExtractedAt(Instant.now());

        CvExperience exp = new CvExperience();
        exp.setEmployer("Acme AB");
        exp.setTitle("Senior Engineer");
        exp.setStartDate("2019");
        exp.setEndDate("present");
        exp.setCurrent(true);
        exp.setOrdinal(0);
        exp.setVerified(true);
        profile.addExperience(exp);

        CvExperienceBullet bullet = new CvExperienceBullet();
        bullet.setText("Built the ingestion pipeline");
        bullet.setOrdinal(0);
        exp.addBullet(bullet);

        CvEducation edu = new CvEducation();
        edu.setInstitution("KTH");
        edu.setDegree("MSc");
        edu.setOrdinal(0);
        edu.setVerified(true);
        profile.addEducation(edu);

        CvSkill skill = new CvSkill();
        skill.setName("Java");
        skill.setOrdinal(0);
        profile.addSkill(skill);

        profiles.save(profile);

        CvProfile loaded = profiles.findFirstByOrderByIdDesc().orElseThrow();
        assertThat(loaded.getFullName()).isEqualTo("Cai");
        assertThat(loaded.getExperiences()).hasSize(1);
        assertThat(loaded.getExperiences().getFirst().getBullets()).hasSize(1);
        assertThat(loaded.getEducation()).hasSize(1);
        assertThat(loaded.getSkills()).hasSize(1);
    }

    @Test
    void documentLookupBySha256Works() {
        document("sha-b");
        assertThat(documents.findBySha256("sha-b")).isPresent();
        assertThat(documents.findBySha256("missing")).isEmpty();
    }

    @Test
    void newestProfileWins() {
        CvProfile first = new CvProfile();
        first.setCvDocument(document("sha-c"));
        first.setFullName("Older");
        first.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(first);

        CvProfile second = new CvProfile();
        second.setCvDocument(document("sha-d"));
        second.setFullName("Newer");
        second.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.save(second);

        assertThat(profiles.findFirstByOrderByIdDesc().orElseThrow().getFullName())
                .isEqualTo("Newer");
    }
}
