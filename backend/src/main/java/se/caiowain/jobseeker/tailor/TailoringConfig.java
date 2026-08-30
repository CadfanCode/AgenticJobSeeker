package se.caiowain.jobseeker.tailor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.caiowain.jobseeker.select.SelectionGuard;
import se.caiowain.jobseeker.select.SelectionPromptBuilder;

@Configuration
public class TailoringConfig {

    /** Kept free of Spring annotations so their tests stay pure. */
    @Bean public SelectionGuard selectionGuard() { return new SelectionGuard(); }
    @Bean public ApplicationAssembler applicationAssembler() { return new ApplicationAssembler(); }
    @Bean public SelectionPromptBuilder tailoringPromptBuilder() { return new SelectionPromptBuilder(); }
}
