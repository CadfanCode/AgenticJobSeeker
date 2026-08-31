package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TriageServiceTest extends AbstractIntegrationTest {

    @Autowired TriageService triage;
    @Autowired PrescreenService prescreen;
    @Autowired JobTriageRepository triages;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    private Long jobId;

    @BeforeEach
    void seed() {
        triages.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        CandidateLanguage sv = new CandidateLanguage();
        sv.setLanguage("sv");
        sv.setLevel(LanguageLevel.NATIVE);
        prefs.addLanguage(sv);
        prefs.setAcceptableMunicipalities("Stockholm");
        preferences.saveAndFlush(prefs);

        profiles.saveAndFlush(FitFixtures.readyProfile(
                documents.save(FitFixtures.document("b".repeat(64))), "Java"));

        JobPosting job = jobs.save(FitFixtures.posting(
                "triage-1", "Utvecklare", "Vi kör Java.", "Stockholm"));
        jobId = job.getId();
    }

    /**
     * {@code job_preferences} is a JVM-wide singleton on a static, shared container, and this
     * class is not {@code @Transactional} — so whatever {@link #seed()} commits to it would
     * otherwise leak into every test class that runs afterwards. Put it back the way V7 seeded
     * it, aside from {@code remotePolicy} and {@code homeMunicipality}, which nothing here
     * changes.
     */
    @AfterEach
    void restorePreferencesSingleton() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        prefs.setAcceptableMunicipalities(null);
        preferences.saveAndFlush(prefs);
    }

    @Test
    void aDecisionIsRecordedWithItsNote() {
        JobTriage decision = triage.decide(jobId, TriageState.SHORTLISTED, "worth a look");

        assertThat(decision.getState()).isEqualTo(TriageState.SHORTLISTED);
        assertThat(decision.getNote()).isEqualTo("worth a look");
        assertThat(decision.getDecidedAt()).isNotNull();
    }

    @Test
    void decidingTwiceUpdatesTheSameRowRatherThanAddingOne() {
        triage.decide(jobId, TriageState.SHORTLISTED, null);
        triage.decide(jobId, TriageState.DISMISSED, "wrong stack after all");

        assertThat(triages.count()).isEqualTo(1);
        assertThat(triages.findByJobPostingId(jobId).orElseThrow().getState())
                .isEqualTo(TriageState.DISMISSED);
    }

    @Test
    void aPrescreenRebuildLeavesDecisionsAlone() {
        // The load-bearing guarantee of the whole slice. A one-second job must never be
        // able to destroy a judgement — or, in Task 8, a 94-second measurement.
        triage.decide(jobId, TriageState.DISMISSED, "not for me");

        prescreen.run();
        prescreen.run();

        assertThat(triages.findByJobPostingId(jobId).orElseThrow().getState())
                .isEqualTo(TriageState.DISMISSED);
    }

    @Test
    void anUnknownJobIsRejected() {
        assertThatThrownBy(() -> triage.decide(999_999L, TriageState.SHORTLISTED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
