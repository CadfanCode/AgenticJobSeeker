package se.caiowain.jobseeker.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.extract.CvProfileExtractor;
import se.caiowain.jobseeker.profile.extract.ExtractedProfile;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
class CvProfileServiceTest extends AbstractIntegrationTest {

    @Autowired CvUploadService uploads;
    @Autowired CvProfileService profileService;
    @Autowired CvDocumentRepository documents;
    @Autowired CvProfileRepository profiles;

    /** The only LLM caller is replaced; no test reaches the network. */
    @MockitoBean CvProfileExtractor extractor;

    private static final String SOURCE = """
            Cai Wain
            Senior Engineer, Acme AB
            2019 - present
            Built the ingestion pipeline
            """;

    private ExtractedProfile canned(String employer) {
        return new ExtractedProfile("Cai Wain", "Senior Engineer", null, null, null, null, "en",
                List.of(new ExtractedProfile.ExtractedExperience(
                        employer, "Senior Engineer", "2019", "present", true, null,
                        List.of("Built the ingestion pipeline"))),
                List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        profiles.deleteAll();
        documents.deleteAll();
        when(extractor.isAvailable()).thenReturn(true);
        when(extractor.modelName()).thenReturn("claude-opus-5");
        when(extractor.extract(anyString())).thenReturn(canned("Acme AB"));
    }

    private CvDocument storedDocument() {
        CvDocument doc = uploads.store("cv.pdf", "application/pdf", "pdf-bytes".getBytes());
        doc.setExtractedText(SOURCE);
        return documents.save(doc);
    }

    @Test
    void ingestPersistsAProfileNeedingReview() {
        CvProfile profile = profileService.ingest(storedDocument());

        assertThat(profile.getStatus()).isEqualTo(ProfileStatus.NEEDS_REVIEW);
        assertThat(profile.getFullName()).isEqualTo("Cai Wain");
        assertThat(profile.getExperiences()).hasSize(1);
        assertThat(profile.getExperiences().getFirst().getBullets()).hasSize(1);
        assertThat(profile.getModelUsed()).isEqualTo("claude-opus-5");
    }

    @Test
    void verifiedExperienceIsNotFlagged() {
        CvProfile profile = profileService.ingest(storedDocument());
        assertThat(profile.getExperiences().getFirst().isVerified()).isTrue();
        assertThat(profile.getExperiences().getFirst().getVerificationNotes()).isNull();
    }

    @Test
    void inventedEmployerIsFlaggedUnverified() {
        doReturn(canned("Globex Corporation")).when(extractor).extract(anyString());

        CvProfile profile = profileService.ingest(storedDocument());

        assertThat(profile.getExperiences().getFirst().isVerified()).isFalse();
        assertThat(profile.getExperiences().getFirst().getVerificationNotes())
                .contains("Globex Corporation");
    }

    @Test
    void extractionFailureIsRecordedAndRecoverable() {
        // doThrow/doReturn rather than when(...): when() invokes the mock, so re-stubbing
        // a method that currently throws would raise during the stubbing call itself.
        doThrow(new CvProfileExtractor.ExtractionFailedException("boom", new RuntimeException()))
                .when(extractor).extract(anyString());

        CvProfile profile = profileService.ingest(storedDocument());
        assertThat(profile.getStatus()).isEqualTo(ProfileStatus.EXTRACTION_FAILED);

        doReturn(canned("Acme AB")).when(extractor).extract(anyString());
        CvProfile retried = profileService.reextract();
        assertThat(retried.getStatus()).isEqualTo(ProfileStatus.NEEDS_REVIEW);
    }

    @Test
    void reUploadingTheSameBytesIsIdempotent() {
        CvDocument first = uploads.store("cv.pdf", "application/pdf", "identical".getBytes());
        CvDocument second = uploads.store("cv-copy.pdf", "application/pdf", "identical".getBytes());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(documents.count()).isEqualTo(1);
    }

    @Test
    void rejectsNonPdfAndOversizeUploads() {
        assertThatThrownBy(() -> uploads.store("cv.txt", "text/plain", "x".getBytes()))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("PDF");

        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> uploads.store("cv.pdf", "application/pdf", tooBig))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("large");
    }

    @Test
    void approveMovesProfileToReady() {
        profileService.ingest(storedDocument());

        CvProfile approved = profileService.approve();

        assertThat(approved.getStatus()).isEqualTo(ProfileStatus.READY);
        assertThat(approved.getReviewedAt()).isNotNull();
    }

    @Test
    void currentProfileIsTheNewest() {
        profileService.ingest(storedDocument());

        CvDocument another = uploads.store("cv2.pdf", "application/pdf", "different-bytes".getBytes());
        another.setExtractedText(SOURCE);
        documents.save(another);
        doReturn(canned("Acme AB")).when(extractor).extract(anyString());
        CvProfile newest = profileService.ingest(another);

        assertThat(profileService.current().orElseThrow().getId()).isEqualTo(newest.getId());
    }
}
