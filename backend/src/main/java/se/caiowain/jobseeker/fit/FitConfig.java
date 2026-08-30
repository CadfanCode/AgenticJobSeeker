package se.caiowain.jobseeker.fit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kept free of Spring annotations so their tests stay pure — same pattern as TailoringConfig. */
@Configuration
public class FitConfig {

    @Bean public LanguageRequirementDetector languageRequirementDetector() {
        return new LanguageRequirementDetector();
    }

    @Bean public GateEvaluator gateEvaluator(LanguageRequirementDetector detector) {
        return new GateEvaluator(detector);
    }
}
