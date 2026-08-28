package se.caiowain.jobseeker.tailor.select;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.caiowain.jobseeker.tailor.TailoringUnavailableException;

import java.util.List;

/**
 * The only component in the tailoring slice that calls a model.
 *
 * <p>The model is configured in application.yml, never here: the Ollama options builder
 * accepts only the {@code OllamaModel} enum, which does not contain qwen2.5:7b-instruct.
 */
@Component
public class OllamaSelectionClient {

    private final ChatClient.Builder chatClientBuilder;
    private final TailoringPromptBuilder prompts;
    private final String model;
    private final String baseUrl;

    public OllamaSelectionClient(ChatClient.Builder chatClientBuilder,
                                 TailoringPromptBuilder prompts,
                                 @Value("${spring.ai.ollama.chat.options.model:}") String model,
                                 @Value("${spring.ai.ollama.base-url:}") String baseUrl) {
        this.chatClientBuilder = chatClientBuilder;
        this.prompts = prompts;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    public boolean isAvailable() {
        return model != null && !model.isBlank();
    }

    public String modelName() {
        return model;
    }

    public SelectionResult select(String jobDescription, List<NumberedBullet> bullets) {
        if (!isAvailable()) {
            throw new TailoringUnavailableException(
                    "Tailoring needs a local model. Start Ollama and set "
                            + "spring.ai.ollama.chat.options.model.");
        }
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(prompts.systemPrompt())
                    .user(prompts.userPrompt(jobDescription, bullets))
                    .options(OllamaChatOptions.builder().format("json"))
                    .call()
                    .entity(SelectionResult.class);
        } catch (Exception e) {
            throw new TailoringUnavailableException(
                    "Could not reach the local model at " + baseUrl + " (" + model + "): "
                            + e.getMessage());
        }
    }
}
