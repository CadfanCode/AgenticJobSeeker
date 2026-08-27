package se.caiowain.jobseeker.profile.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.profile.extract.CvProfileExtractor;
import se.caiowain.jobseeker.profile.extract.ExtractedProfile;
import se.caiowain.jobseeker.profile.extract.ExtractionUnavailableException;
import se.caiowain.jobseeker.profile.extract.PdfTextExtractor;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CvProfileRepository profiles;
    @Autowired CvDocumentRepository documents;

    @MockitoBean CvProfileExtractor extractor;
    @MockitoBean PdfTextExtractor pdfTextExtractor;

    private static final String SOURCE = "Cai Wain\nSenior Engineer, Acme AB\n2019 - present\n";

    @BeforeEach
    void setUp() {
        profiles.deleteAll();
        documents.deleteAll();
        doReturn(SOURCE).when(pdfTextExtractor).extract(any());
        when(extractor.isAvailable()).thenReturn(true);
        when(extractor.modelName()).thenReturn("claude-opus-5");
        doReturn(new ExtractedProfile(
                "Cai Wain", "Senior Engineer", null, null, null, null, "en",
                List.of(new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2019", "present", true, null,
                        List.of("Built the ingestion pipeline"))),
                List.of(), List.of())).when(extractor).extract(anyString());
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    }

    @Test
    void returns404WhenNoProfileExists() throws Exception {
        mvc.perform(get("/api/profile")).andExpect(status().isNotFound());
    }

    @Test
    void uploadExtractsAndReturnsTheProfile() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Cai Wain"))
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.experiences[0].employer").value("Acme AB"))
                .andExpect(jsonPath("$.experiences[0].bullets[0].text").value("Built the ingestion pipeline"))
                .andExpect(jsonPath("$.experiences[0].verified").value(true));
    }

    @Test
    void rejectsNonPdfWith400() throws Exception {
        mvc.perform(multipart("/api/profile/upload")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns503WhenNoApiKeyIsConfigured() throws Exception {
        when(extractor.isAvailable()).thenReturn(false);
        doThrow(new ExtractionUnavailableException(
                "CV extraction needs a model credential. Set ANTHROPIC_API_KEY and restart."))
                .when(extractor).extract(anyString());

        mvc.perform(multipart("/api/profile/upload").file(pdf()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void servesTheRawSourceTextForSideBySideReview() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(get("/api/profile/source-text"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Senior Engineer, Acme AB")));
    }

    @Test
    void savesCorrections() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(put("/api/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Cai W.","headline":"Staff Engineer","language":"en",
                                 "experiences":[{"employer":"Acme AB","title":"Staff Engineer",
                                   "startDate":"2019","endDate":"present","current":true,"ordinal":0,
                                   "verified":true,"bullets":[{"text":"Rewrote the pipeline","ordinal":0}]}],
                                 "education":[],"skills":[{"name":"Java","ordinal":0}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Cai W."))
                .andExpect(jsonPath("$.experiences[0].title").value("Staff Engineer"))
                .andExpect(jsonPath("$.skills[0].name").value("Java"));
    }

    @Test
    void reUploadingTheSameFileReturns200AndDoesNotExtractAgain() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(multipart("/api/profile/upload").file(pdf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Cai Wain"));

        verify(extractor, times(1)).extract(anyString());
        org.assertj.core.api.Assertions.assertThat(documents.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(profiles.count()).isEqualTo(1);
    }

    @Test
    void approveSetsReady() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(post("/api/profile/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void reextractRunsAgainFromStoredText() throws Exception {
        mvc.perform(multipart("/api/profile/upload").file(pdf())).andExpect(status().isCreated());

        mvc.perform(post("/api/profile/reextract"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"));
    }
}
