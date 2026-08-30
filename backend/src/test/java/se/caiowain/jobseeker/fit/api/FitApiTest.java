package se.caiowain.jobseeker.fit.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.FitFixtures;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.RemotePolicy;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.select.ModelUnavailableException;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionResult;
import se.caiowain.jobseeker.select.SelectionResult.RequirementSelection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FitApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JobPostingRepository jobs;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobDeepFitRepository deepFits;
    @Autowired JobTriageRepository triages;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;
    @Autowired JobPreferencesRepository preferences;

    @MockitoBean OllamaSelectionClient selectionClient;

    private static final String DESCRIPTION =
            "Vi söker en utvecklare med erfarenhet av Java och REST-API:er.";

    private Long jobId;

    @BeforeEach
    void seed() {
        deepFits.deleteAllInBatch();
        triages.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        jobId = jobs.save(FitFixtures.posting(
                "api-1", "Utvecklare", DESCRIPTION, "Stockholm")).getId();

        CvProfile profile = FitFixtures.readyProfile(
                documents.save(FitFixtures.document("d".repeat(64))), "Java");
        FitFixtures.withBullets(profile, "Byggde REST-API:er i Java");
        profiles.saveAndFlush(profile);

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
        doReturn(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1))).when(selectionClient).select(anyString(), any());
    }

    /**
     * {@code job_preferences} is a JVM-wide singleton on a static, shared container, and this
     * class is not {@code @Transactional} — so whatever {@code preferencesRoundTripIncludingLanguages}
     * and {@code savingPreferencesTwiceReplacesLanguagesRatherThanAppending} commit to it would
     * otherwise leak into every test class that runs afterwards (e.g.
     * {@code PreferencesPersistenceTest}, which asserts {@code hasSize(1)} on its languages).
     * Put it back the way V7 seeded it: no languages, no municipalities, no deal breakers,
     * remote policy back to {@code HYBRID_OK}.
     */
    @AfterEach
    void restorePreferencesSingleton() {
        JobPreferences prefs = preferences.findSingleton();
        prefs.getLanguages().clear();
        prefs.setAcceptableMunicipalities(null);
        prefs.setHomeMunicipality(null);
        prefs.setDealBreakers(null);
        prefs.setRemotePolicy(RemotePolicy.HYBRID_OK);
        preferences.saveAndFlush(prefs);
    }

    @Test
    void prescreenReportsWhatItCovered() throws Exception {
        mvc.perform(post("/api/fit/prescreen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postings").value(1));
    }

    @Test
    void deepScoringReturnsCoverageAndGaps() throws Exception {
        mvc.perform(post("/api/jobs/" + jobId + "/fit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coveragePercent").value(100))
                .andExpect(jsonPath("$.requirementCount").value(1));
    }

    @Test
    void aDeepScoreCanBeReadBackAndIsAbsentUntilOneIsRun() throws Exception {
        mvc.perform(get("/api/jobs/" + jobId + "/fit")).andExpect(status().isNotFound());

        mvc.perform(post("/api/jobs/" + jobId + "/fit")).andExpect(status().isOk());

        mvc.perform(get("/api/jobs/" + jobId + "/fit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coveragePercent").value(100));
    }

    @Test
    void anUnreachableModelIsFiveOhThreeNotAZeroScore() throws Exception {
        doThrow(new ModelUnavailableException("Could not reach the local model at :11434"))
                .when(selectionClient).select(anyString(), any());

        mvc.perform(post("/api/jobs/" + jobId + "/fit"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void prescreeningWithoutAnApprovedProfileIsFourOhNine() throws Exception {
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        mvc.perform(post("/api/fit/prescreen")).andExpect(status().isConflict());
    }

    @Test
    void scoringAnUnknownJobIsFourOhFour() throws Exception {
        mvc.perform(post("/api/jobs/999999/fit")).andExpect(status().isNotFound());
    }

    @Test
    void aTriageDecisionIsStored() throws Exception {
        mvc.perform(put("/api/jobs/" + jobId + "/triage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"DISMISSED\",\"note\":\"wrong stack\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DISMISSED"));

        assertThat(triages.findByJobPostingId(jobId).orElseThrow().getState())
                .isEqualTo(TriageState.DISMISSED);
    }

    @Test
    void anUnknownTriageStateIsRejectedAsABadRequest() throws Exception {
        mvc.perform(put("/api/jobs/" + jobId + "/triage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"MAYBE_LATER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTriageRequestWithNoStateIsRejectedAsABadRequest() throws Exception {
        mvc.perform(put("/api/jobs/" + jobId + "/triage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preferencesRoundTripIncludingLanguages() throws Exception {
        mvc.perform(put("/api/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"homeMunicipality":"Stockholm",
                                 "acceptableMunicipalities":"Stockholm, Solna",
                                 "remotePolicy":"HYBRID_OK",
                                 "dealBreakers":"no on-call",
                                 "languages":[{"language":"sv","level":"CONVERSATIONAL"}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remotePolicy").value("HYBRID_OK"))
                .andExpect(jsonPath("$.languages[0].language").value("sv"))
                .andExpect(jsonPath("$.languages[0].level").value("CONVERSATIONAL"));
    }

    @Test
    void savingPreferencesTwiceReplacesLanguagesRatherThanAppending() throws Exception {
        String body = """
                {"remotePolicy":"ONSITE_ONLY",
                 "languages":[{"language":"sv","level":"NATIVE"}]}
                """;

        mvc.perform(put("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        mvc.perform(put("/api/preferences")
                .contentType(MediaType.APPLICATION_JSON).content(body));

        mvc.perform(get("/api/preferences"))
                .andExpect(jsonPath("$.languages.length()").value(1));
    }
}
