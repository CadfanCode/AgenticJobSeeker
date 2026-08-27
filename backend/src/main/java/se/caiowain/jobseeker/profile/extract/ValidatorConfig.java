package se.caiowain.jobseeker.profile.extract;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidatorConfig {

    /** Kept free of Spring annotations so its tests stay pure. */
    @Bean
    public ExtractionValidator extractionValidator() {
        return new ExtractionValidator();
    }
}
