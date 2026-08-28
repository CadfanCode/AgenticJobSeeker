package se.caiowain.jobseeker.profile.extract;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CvProfileExtractorTest {

    private static final ExtractedProfile CANNED = new ExtractedProfile(
            "Cai Wain", "Senior Engineer", null, null, null, null, "en",
            List.of(new ExtractedProfile.ExtractedExperience(
                    "Acme AB", "Senior Engineer", "2019", "present", true, null,
                    List.of("Built the ingestion pipeline"))),
            List.of(), List.of());

    /** Builds a ChatClient.Builder whose whole fluent chain is mocked. */
    private ChatClient.Builder stubbedBuilder(ExtractedProfile result) {
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(response.entity(ExtractedProfile.class)).thenReturn(result);

        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any())).thenReturn(request);
        when(request.call()).thenReturn(response);

        ChatClient client = mock(ChatClient.class);
        when(client.prompt()).thenReturn(request);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        return builder;
    }

    @Test
    void reportsUnavailableWhenNoApiKeyIsConfigured() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "", "http://localhost:11434");

        assertThat(extractor.isAvailable()).isFalse();
        assertThatThrownBy(() -> extractor.extract("some cv text"))
                .isInstanceOf(ExtractionUnavailableException.class)
                .hasMessageContaining("Ollama");
    }

    @Test
    void isAvailableWhenAKeyIsConfigured() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "qwen2.5:7b-instruct", "http://localhost:11434");
        assertThat(extractor.isAvailable()).isTrue();
    }

    @Test
    void returnsTheStructuredProfileFromTheModel() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "qwen2.5:7b-instruct", "http://localhost:11434");

        ExtractedProfile result = extractor.extract("Cai Wain\nSenior Engineer, Acme AB\n2019 - present");

        assertThat(result.fullName()).isEqualTo("Cai Wain");
        assertThat(result.experiences()).hasSize(1);
        assertThat(result.experiences().getFirst().employer()).isEqualTo("Acme AB");
    }

    @Test
    void passesTheSourceTextToTheModel() {
        ChatClient.Builder builder = stubbedBuilder(CANNED);
        var extractor = new CvProfileExtractor(builder, "qwen2.5:7b-instruct", "http://localhost:11434");

        extractor.extract("MARKER-TEXT-12345");

        ChatClient.ChatClientRequestSpec request = builder.build().prompt();
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(request, atLeastOnce()).user(userMessage.capture());
        assertThat(userMessage.getAllValues()).anyMatch(v -> v.contains("MARKER-TEXT-12345"));
    }

    @Test
    void reportsTheConfiguredModelName() {
        var extractor = new CvProfileExtractor(stubbedBuilder(CANNED), "qwen2.5:7b-instruct", "http://localhost:11434");
        assertThat(extractor.modelName()).isEqualTo("qwen2.5:7b-instruct");
    }

    @Test
    void wrapsModelFailuresInAnExtractionException() {
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(response.entity(ExtractedProfile.class)).thenThrow(new RuntimeException("upstream 529"));

        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any())).thenReturn(request);
        when(request.call()).thenReturn(response);

        ChatClient client = mock(ChatClient.class);
        when(client.prompt()).thenReturn(request);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);

        var extractor = new CvProfileExtractor(builder, "qwen2.5:7b-instruct", "http://localhost:11434");

        assertThatThrownBy(() -> extractor.extract("text"))
                .isInstanceOf(CvProfileExtractor.ExtractionFailedException.class)
                .hasMessageContaining("upstream 529");
    }
}
