package se.caiowain.jobseeker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Any port on the loopback host, not one pinned port.
     *
     * <p>Vite takes 5173 when it is free and moves to 5174, 5175 and onward when it is not —
     * another project's dev server running at the same time is enough. Pinning 5173 meant that
     * whenever it moved, reading the app kept working while every save failed with 403: a
     * browser attaches an {@code Origin} header to POST, PUT and DELETE even when the request
     * is same-origin, but omits it on a same-origin GET. The symptom looked like a broken
     * button rather than a rejected origin.
     *
     * <p>{@code allowedOriginPatterns} rather than {@code allowedOrigins} because the latter
     * takes literal strings only. The wildcard is over the <em>port</em>, on loopback hosts
     * alone — a page served from anywhere else still cannot drive this API.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
