package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PrescreenServiceTest extends AbstractIntegrationTest {

    @Autowired PrescreenService prescreen;
    @Autowired JobPostingRepository jobs;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    @BeforeEach
    void reset() {
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        CandidateLanguage swedish = new CandidateLanguage();
        swedish.setLanguage("sv");
        swedish.setLevel(LanguageLevel.NATIVE);
        prefs.addLanguage(swedish);
        prefs.setAcceptableMunicipalities("Stockholm");
        preferences.saveAndFlush(prefs);

        CvProfile profile = FitFixtures.readyProfile(
                documents.save(FitFixtures.document("a".repeat(64))),
                "Java", "Kubernetes", "Spring Boot", "COBOL");
        profiles.saveAndFlush(profile);
    }

    /**
     * {@code job_preferences} is a JVM-wide singleton on a static, shared container, and this
     * class is not {@code @Transactional} — so whatever {@link #reset()} commits to it would
     * otherwise leak into every test class that runs afterwards. Put it back the way V7 seeded
     * it, aside from {@code remotePolicy}, which nothing here changes.
     */
    @AfterEach
    void restorePreferencesSingleton() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        prefs.setAcceptableMunicipalities(null);
        preferences.saveAndFlush(prefs);
    }

    private Optional<JobPrescreen> prescreenFor(String fingerprint) {
        return jobs.findByFingerprint(fingerprint).stream()
                .findFirst()
                .flatMap(job -> prescreens.findByJobPostingId(job.getId()));
    }

    @Test
    void englishSkillTermsMatchInsideASwedishAd() {
        // The assumption the whole lexical approach rests on: Swedish IT ads name their
        // tools in English, so a skill row matches verbatim whatever language the ad is in.
        jobs.save(FitFixtures.posting("sv-ad", "Backend-utvecklare",
                "Vi söker en utvecklare som arbetat med Java och Kubernetes i produktion.",
                "Stockholm"));

        prescreen.run();

        JobPrescreen row = prescreenFor("sv-ad").orElseThrow();
        assertThat(row.getMatchedSkillCount()).isEqualTo(2);
        assertThat(row.getMatchedSkills()).contains("Java").contains("Kubernetes");
        assertThat(row.getMatchedSkills()).doesNotContain("COBOL");
    }

    @Test
    void aMultiWordSkillNeedsItsWordsAdjacent() {
        // phraseto_tsquery, not plainto_tsquery: an ad mentioning Spring in one sentence
        // and boot in another must not count as Spring Boot.
        jobs.save(FitFixtures.posting("loose", "Utvecklare",
                "Vi arbetar med Spring i backend. Vi har även ett boot camp för nyanställda.",
                "Stockholm"));

        prescreen.run();

        assertThat(prescreenFor("loose").orElseThrow().getMatchedSkills())
                .doesNotContain("Spring Boot");
    }

    @Test
    void anAdjacentMultiWordSkillDoesMatch() {
        jobs.save(FitFixtures.posting("adjacent", "Utvecklare",
                "Vi bygger tjänster i Spring Boot och deployar dem dagligen.", "Stockholm"));

        assertThat(prescreenFor("adjacent")).isEmpty();
        prescreen.run();

        assertThat(prescreenFor("adjacent").orElseThrow().getMatchedSkills())
                .contains("Spring Boot");
    }

    @Test
    void everyPostingGetsARowEvenWhenNothingMatches() {
        // A zero-match posting still needs its gates evaluated and still has to appear
        // in the list, so the rebuild covers the corpus rather than only the hits.
        jobs.save(FitFixtures.posting("nomatch", "Redovisningsekonom",
                "Vi söker en ekonom till vårt kontor.", "Stockholm"));

        prescreen.run();

        JobPrescreen row = prescreenFor("nomatch").orElseThrow();
        assertThat(row.getMatchedSkillCount()).isZero();
        assertThat(row.getLocationGate()).isEqualTo(GateVerdict.PASS);
    }

    @Test
    void gatesAreEvaluatedAndTheirEvidenceStored() {
        jobs.save(FitFixtures.posting("gated", "Utvecklare",
                "Vi kräver flytande engelska. Java är ett plus.", "Malmö"));

        prescreen.run();

        JobPrescreen row = prescreenFor("gated").orElseThrow();
        assertThat(row.getLanguageGate()).isEqualTo(GateVerdict.FAIL);
        assertThat(row.getLanguageNote()).isEqualTo("flytande engelska");
        assertThat(row.getLocationGate()).isEqualTo(GateVerdict.FAIL);
    }

    @Test
    void aRerunReplacesRowsRatherThanAccumulatingThem() {
        jobs.save(FitFixtures.posting("once", "Utvecklare", "Java och Kubernetes.", "Stockholm"));

        prescreen.run();
        prescreen.run();
        prescreen.run();

        assertThat(prescreens.count()).isEqualTo(1);
    }

    @Test
    void theSummaryReportsWhatItCovered() {
        jobs.save(FitFixtures.posting("hit", "Utvecklare", "Vi kör Java.", "Stockholm"));
        jobs.save(FitFixtures.posting("miss", "Ekonom", "Vi söker en ekonom.", "Stockholm"));

        PrescreenSummary summary = prescreen.run();

        assertThat(summary.postings()).isEqualTo(2);
        assertThat(summary.withMatches()).isEqualTo(1);
    }

    @Test
    void aProfileThatHasNotBeenApprovedStopsTheRun() {
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(se.caiowain.jobseeker.profile.domain.ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> prescreen.run())
                .isInstanceOf(se.caiowain.jobseeker.profile.ProfileNotReadyException.class);
    }

    @Test
    void theQuietRunSkipsRatherThanFailsWhenNoProfileIsApproved() {
        // Ingest happens long before a CV is approved. A missing profile must not turn a
        // successful ingest run into a failed one.
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(se.caiowain.jobseeker.profile.domain.ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        assertThat(prescreen.runQuietly()).isEmpty();
    }
}
