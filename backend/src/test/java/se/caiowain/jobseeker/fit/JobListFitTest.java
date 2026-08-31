package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.fit.domain.CandidateLanguage;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.LanguageLevel;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JobListFitTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired PrescreenService prescreen;
    @Autowired TriageService triage;
    @Autowired JobPostingRepository jobs;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobTriageRepository triages;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    private Long strongId;
    private Long weakId;
    private Long vetoedId;

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
                documents.save(FitFixtures.document("e".repeat(64))),
                "Java", "Kubernetes", "Docker"));

        // Distinct publishedAt values, deliberately: FitFixtures.posting gives every
        // posting the same timestamp, which would make an ordering assertion over the
        // default (newest-first) view pass or fail by accident. strong is the newer of the
        // two visible postings, so theDefaultSortIsStillNewestFirst has a real order to check.
        JobPosting strong = FitFixtures.posting("strong", "Plattformsingenjör",
                "Vi kör Java, Kubernetes och Docker i produktion.", "Stockholm");
        strong.setPublishedAt(Instant.parse("2026-08-30T08:00:00Z"));
        strongId = jobs.save(strong).getId();

        JobPosting weak = FitFixtures.posting("weak", "Utvecklare",
                "Vi kör Java i produktion.", "Stockholm");
        weak.setPublishedAt(Instant.parse("2026-08-28T08:00:00Z"));
        weakId = jobs.save(weak).getId();

        vetoedId = jobs.save(FitFixtures.posting("vetoed", "Konsult",
                "Vi kräver flytande engelska. Vi kör Java, Kubernetes och Docker.",
                "Stockholm")).getId();

        prescreen.run();
    }

    /**
     * {@code job_preferences} is a JVM-wide singleton on a static, shared container, and this
     * class is not {@code @Transactional} — so whatever {@link #seed()} commits to it would
     * otherwise leak into every test class that runs afterwards (e.g.
     * {@code PreferencesPersistenceTest}'s {@code hasSize(1)} assertion). Put it back the way
     * V7 seeded it.
     */
    @AfterEach
    void restorePreferencesSingleton() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        prefs.setAcceptableMunicipalities(null);
        preferences.saveAndFlush(prefs);
    }

    @Test
    void sortingByFitPutsTheAdNamingMostOfYourSkillsFirst() throws Exception {
        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(strongId))
                .andExpect(jsonPath("$.content[0].matchedSkillCount").value(3))
                .andExpect(jsonPath("$.content[1].id").value(weakId));
    }

    @Test
    void theMatchedSkillNamesTravelWithTheRowSoTheCountIsCheckable() throws Exception {
        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(jsonPath("$.content[0].matchedSkills").value(
                        org.hamcrest.Matchers.containsString("Kubernetes")));
    }

    @Test
    void gateFailuresAreHiddenByDefaultAndShownOnRequestWithTheirEvidence() throws Exception {
        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/jobs?sort=skills&includeGateFailures=true"))
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get("/api/jobs?includeGateFailures=true&q=Konsult"))
                .andExpect(jsonPath("$.content[0].languageGate").value("FAIL"))
                .andExpect(jsonPath("$.content[0].languageNote").value("flytande engelska"));
    }

    @Test
    void aDismissedJobDropsOutOfTheDefaultListAndCanBeAskedForBack() throws Exception {
        triage.decide(weakId, TriageState.DISMISSED, null);

        mvc.perform(get("/api/jobs"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/jobs?triage=DISMISSED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(weakId));
    }

    @Test
    void theTriageStateTravelsWithTheRow() throws Exception {
        triage.decide(strongId, TriageState.SHORTLISTED, null);

        mvc.perform(get("/api/jobs?triage=SHORTLISTED"))
                .andExpect(jsonPath("$.content[0].triage").value("SHORTLISTED"));
    }

    @Test
    void postingsWithNoPrescreenRowStillAppear() throws Exception {
        // A job ingested after the last prescreen must not vanish from the list — the
        // fit join is a LEFT join for exactly this case.
        jobs.save(FitFixtures.posting("fresh", "Nyinkommen",
                "Vi kör Java.", "Stockholm"));

        mvc.perform(get("/api/jobs?sort=skills"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void theDefaultSortIsStillNewestFirst() throws Exception {
        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(strongId))
                .andExpect(jsonPath("$.content[1].id").value(weakId));
    }
}
