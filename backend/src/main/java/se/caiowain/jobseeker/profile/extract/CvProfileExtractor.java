package se.caiowain.jobseeker.profile.extract;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only component in the application that calls a language model.
 *
 * <p>Availability is decided by reading the configured API key, not by checking for a bean:
 * Spring AI's autoconfiguration creates {@code ChatModel} and {@code ChatClient.Builder}
 * beans even when no key is set, so bean presence proves nothing.
 */
@Component
public class CvProfileExtractor {

    private static final String SYSTEM_PROMPT = """
            You extract structured data from a CV. You are a careful transcriber, not a writer.

            Rules:
            - Copy employer names, job titles, institutions and dates EXACTLY as they appear.
              Never normalise, expand, translate or correct them.
            - Write dates exactly as the CV writes them: "2019", "Mar 2020", "present".
              Do not convert to a standard format and do not infer missing parts.
            - Bullet points may be reflowed to repair line wrapping, but never reworded,
              summarised or embellished.
            - If a field is not present in the CV, return null. Never guess and never invent
              an employer, title, qualification or date that is not written in the source.
            - Set language to the ISO 639-1 code of the language the CV is written in.
            - Order experiences and education most recent first.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final String apiKey;
    private final String model;
    private final String effort;

    public CvProfileExtractor(ChatClient.Builder chatClientBuilder,
                              @Value("${spring.ai.anthropic.api-key:}") String apiKey,
                              @Value("${spring.ai.anthropic.chat.options.model:claude-opus-5}") String model,
                              @Value("${jobseeker.profile.extraction-effort:HIGH}") String effort) {
        this.chatClientBuilder = chatClientBuilder;
        this.apiKey = apiKey;
        this.model = model;
        this.effort = effort;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelName() {
        return model;
    }

    public ExtractedProfile extract(String sourceText) {
        if (!isAvailable()) {
            throw new ExtractionUnavailableException(
                    "CV extraction needs a model credential. Set ANTHROPIC_API_KEY and restart.");
        }
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user("Extract this CV:\n\n" + sourceText)
                    .options(AnthropicChatOptions.builder()
                            .thinking(ThinkingConfigParam.ofAdaptive(
                                    ThinkingConfigAdaptive.builder().build()))
                            .effort(OutputConfig.Effort.of(effort)))
                    .call()
                    .entity(ExtractedProfile.class);
        } catch (Exception e) {
            throw new ExtractionFailedException("Extraction failed: " + e.getMessage(), e);
        }
    }

    /** The model was reachable but the call did not produce a profile. Recoverable via re-extract. */
    public static class ExtractionFailedException extends RuntimeException {
        public ExtractionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
