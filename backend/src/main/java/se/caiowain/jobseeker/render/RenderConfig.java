package se.caiowain.jobseeker.render;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kept free of Spring annotations so their tests stay pure — as TailoringConfig and FitConfig do. */
@Configuration
public class RenderConfig {

    @Bean public ApplicationDocument applicationDocument() { return new ApplicationDocument(); }

    @Bean public DocumentHtmlBuilder documentHtmlBuilder() { return new DocumentHtmlBuilder(); }
}
