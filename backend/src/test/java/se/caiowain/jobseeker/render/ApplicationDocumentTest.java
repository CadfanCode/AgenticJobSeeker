package se.caiowain.jobseeker.render;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.ApplicationEvidence;
import se.caiowain.jobseeker.tailor.domain.ApplicationRequirement;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationDocumentTest {

    private final ApplicationDocument document = new ApplicationDocument();

    private CvProfile profileWithBullets(String... bulletTexts) {
        CvProfile profile = new CvProfile();
        profile.setFullName("Cai Wain");
        profile.setHeadline("Senior Software Engineer");
        profile.setEmail("cai@example.com");
        profile.setLocation("Stockholm, Sweden");

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
        return profile;
    }

    private TailoredApplication applicationCiting(String... evidenceTexts) {
        JobPosting job = new JobPosting();
        job.setTitle("Plattformsingenjör");
        job.setEmployerName("Example AB");

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);

        ApplicationRequirement requirement = new ApplicationRequirement();
        requirement.setText("erfarenhet av Java");
        requirement.setOrdinal(0);
        int ordinal = 0;
        for (String text : evidenceTexts) {
            ApplicationEvidence evidence = new ApplicationEvidence();
            evidence.setBulletText(text);
            evidence.setOrdinal(ordinal++);
            requirement.addEvidence(evidence);
        }
        application.addRequirement(requirement);
        return application;
    }

    @Test
    void keepsEveryBulletAndPutsMatchedOnesFirst() {
        // Filtering to matched bullets alone would silently drop real work history. Ordering
        // expresses relevance without hiding anything.
        CvProfile profile = profileWithBullets("Wrote documentation", "Built REST APIs in Java",
                "Ran the release process");
        TailoredApplication application = applicationCiting("Built REST APIs in Java");

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences()).hasSize(1);
        assertThat(cv.experiences().getFirst().bullets()).containsExactly(
                "Built REST APIs in Java",
                "Wrote documentation",
                "Ran the release process");
    }

    @Test
    void unmatchedBulletsKeepTheirProfileOrderAmongThemselves() {
        CvProfile profile = profileWithBullets("A", "B", "C", "D");
        TailoredApplication application = applicationCiting("C");

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences().getFirst().bullets()).containsExactly("C", "A", "B", "D");
    }

    @Test
    void severalMatchedBulletsKeepTheirProfileOrderAmongThemselves() {
        CvProfile profile = profileWithBullets("A", "B", "C", "D");
        TailoredApplication application = applicationCiting("D", "B");

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences().getFirst().bullets()).containsExactly("B", "D", "A", "C");
    }

    @Test
    void bulletTextIsCarriedThroughByteForByte() {
        // The renderer adds layout, never content. A bullet on the page must be the bullet
        // in the database — this is Slice 2b's guarantee re-asserted at the render layer.
        CvProfile profile = profileWithBullets("Reduced p99 latency by 40% — measured & verified");
        TailoredApplication application = applicationCiting();

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences().getFirst().bullets())
                .containsExactly("Reduced p99 latency by 40% — measured & verified");
    }

    @Test
    void anExperienceWithNoMatchesStillAppears() {
        CvProfile profile = profileWithBullets("A", "B");
        TailoredApplication application = applicationCiting();

        CvContent cv = document.cv(application, profile);

        assertThat(cv.experiences()).hasSize(1);
        assertThat(cv.experiences().getFirst().bullets()).containsExactly("A", "B");
    }

    @Test
    void theLetterCarriesTheProseTheCandidateWrote() {
        CvProfile profile = profileWithBullets("A");
        TailoredApplication application = applicationCiting();
        application.setLetterProse("Hej,\n\nJag söker tjänsten som plattformsingenjör.");

        LetterContent letter = document.letter(application, profile,
                LocalDate.parse("2026-08-31"));

        assertThat(letter.body()).isEqualTo("Hej,\n\nJag söker tjänsten som plattformsingenjör.");
        assertThat(letter.candidateName()).isEqualTo("Cai Wain");
        assertThat(letter.employerName()).isEqualTo("Example AB");
        assertThat(letter.jobTitle()).isEqualTo("Plattformsingenjör");
        assertThat(letter.date()).isEqualTo(LocalDate.parse("2026-08-31"));
    }

    @Test
    void anEmptyLetterBodyIsAllowed() {
        // Spec section 3.4: approval is the candidate's judgement, and a form that wants only
        // a CV is common. The letter still renders.
        CvProfile profile = profileWithBullets("A");
        TailoredApplication application = applicationCiting();
        application.setLetterProse(null);

        LetterContent letter = document.letter(application, profile,
                LocalDate.parse("2026-08-31"));

        assertThat(letter.body()).isEmpty();
    }
}
