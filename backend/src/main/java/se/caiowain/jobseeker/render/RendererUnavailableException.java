package se.caiowain.jobseeker.render;

/** No browser to render with. Maps to HTTP 503, like an unreachable Ollama. */
public class RendererUnavailableException extends RuntimeException {
    public RendererUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
