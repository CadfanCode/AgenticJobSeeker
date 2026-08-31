package se.caiowain.jobseeker.fit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;
import se.caiowain.jobseeker.fit.domain.JobDeepFitGap;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.select.ModelUnavailableException;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionRejectedException;
import se.caiowain.jobseeker.select.SelectionResult;
import se.caiowain.jobseeker.select.SelectionResult.RequirementSelection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
class DeepFitServiceTest extends AbstractIntegrationTest {

    @Autowired DeepFitService deepFit;
    @Autowired PrescreenService prescreen;
    @Autowired JobDeepFitRepository deepFits;
    @Autowired JobPrescreenRepository prescreens;
    @Autowired JobPostingRepository jobs;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    /** The only model caller is replaced; no test reaches the network. */
    @MockitoBean OllamaSelectionClient selectionClient;

    /** Every requirement below is a verbatim substring of this, or Guard B rejects it. */
    private static final String DESCRIPTION = """
            Vi söker en utvecklare. Du har erfarenhet av Java och bygger REST-API:er.
            Vi ser gärna att du har arbetat med Kubernetes och att du kan AWS.
            """;

    private Long jobId;

    @BeforeEach
    void seed() {
        deepFits.deleteAllInBatch();
        prescreens.deleteAllInBatch();
        jobs.deleteAllInBatch();
        profiles.deleteAllInBatch();
        documents.deleteAllInBatch();

        JobPosting job = FitFixtures.posting("deep-1", "Utvecklare", DESCRIPTION, "Stockholm");
        jobId = jobs.save(job).getId();

        CvProfile profile = FitFixtures.readyProfile(
                documents.save(FitFixtures.document("c".repeat(64))), "Java");
        FitFixtures.withBullets(profile,
                "Byggde REST-API:er i Java och Spring Boot",
                "Drev migrering av en monolit till moduler");
        profiles.saveAndFlush(profile);

        when(selectionClient.isAvailable()).thenReturn(true);
        when(selectionClient.modelName()).thenReturn("qwen2.5:7b-instruct");
    }

    private void modelReturns(SelectionResult result) {
        doReturn(result).when(selectionClient).select(anyString(), any());
    }

    @Test
    void coverageIsComputedInJavaFromCountedRows() {
        // Three requirements, two supported: 2/3 = 67. The model supplies no number here.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1)),
                new RequirementSelection("bygger REST-API:er", List.of(1, 2)),
                new RequirementSelection("arbetat med Kubernetes", List.of())),
                List.of(1, 2)));

        JobDeepFit fit = deepFit.score(jobId);

        assertThat(fit.getRequirementCount()).isEqualTo(3);
        assertThat(fit.getCoveragePercent()).isEqualTo(67);
    }

    @Test
    void theGapsAreTheRequirementsNothingSupports() {
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1)),
                new RequirementSelection("arbetat med Kubernetes", List.of()),
                new RequirementSelection("du kan AWS", List.of())),
                List.of(1)));

        JobDeepFit fit = deepFit.score(jobId);

        assertThat(fit.getGaps()).extracting(JobDeepFitGap::getText)
                .containsExactly("arbetat med Kubernetes", "du kan AWS");
    }

    @Test
    void fullCoverageLeavesNoGaps() {
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1)));

        JobDeepFit fit = deepFit.score(jobId);

        assertThat(fit.getCoveragePercent()).isEqualTo(100);
        assertThat(fit.getGaps()).isEmpty();
    }

    @Test
    void aPhraseAbsentFromTheAdIsRejectedAfterOneRetryAndNothingIsPersisted() {
        // Guard B. The Spike 1 failure was the model reattributing an ad's words to the
        // candidate; a phrase that is not in the ad is not a quotation.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("tio års erfarenhet av Rust", List.of(1))),
                List.of(1)));

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(SelectionRejectedException.class);

        assertThat(deepFits.count()).isZero();
    }

    @Test
    void aBulletNumberThatDoesNotExistIsRejected() {
        // Guard A. The profile has two bullets; 9 is not one of them.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(9))),
                List.of(9)));

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(SelectionRejectedException.class);

        assertThat(deepFits.count()).isZero();
    }

    @Test
    void rescoringReplacesTheEarlierResultRatherThanAddingOne() {
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1)),
                new RequirementSelection("du kan AWS", List.of())),
                List.of(1)));
        deepFit.score(jobId);

        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1)));
        JobDeepFit second = deepFit.score(jobId);

        assertThat(deepFits.count()).isEqualTo(1);
        assertThat(second.getCoveragePercent()).isEqualTo(100);
        assertThat(second.getGaps()).isEmpty();
    }

    @Test
    void aPrescreenRebuildLeavesADeepScoreAlone() {
        // 94 seconds of work must survive a one-second job.
        modelReturns(new SelectionResult(List.of(
                new RequirementSelection("erfarenhet av Java", List.of(1))),
                List.of(1)));
        deepFit.score(jobId);

        prescreen.run();
        prescreen.run();

        assertThat(deepFits.count()).isEqualTo(1);
        assertThat(deepFits.findAll().getFirst().getCoveragePercent()).isEqualTo(100);
    }

    @Test
    void anUnreachableModelSurfacesAsUnavailableRatherThanAsAZeroScore() {
        doThrow(new ModelUnavailableException("Could not reach the local model"))
                .when(selectionClient).select(anyString(), any());

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(ModelUnavailableException.class);

        assertThat(deepFits.count()).isZero();
    }

    @Test
    void anUnapprovedProfileStopsTheRun() {
        CvProfile profile = profiles.findFirstByOrderByIdDesc().orElseThrow();
        profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        profiles.saveAndFlush(profile);

        assertThatThrownBy(() -> deepFit.score(jobId))
                .isInstanceOf(ProfileNotReadyException.class);
    }

    @Test
    void anUnknownJobIsRejected() {
        assertThatThrownBy(() -> deepFit.score(999_999L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
