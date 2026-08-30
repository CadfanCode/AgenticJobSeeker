package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobPreferences;

public interface JobPreferencesRepository extends JpaRepository<JobPreferences, Long> {

    /**
     * There is exactly one row, seeded by V7. Failing loudly beats silently creating a
     * second one: two preference rows would make the gates depend on which was read.
     */
    default JobPreferences findSingleton() {
        return findById(1L).orElseThrow(() ->
                new IllegalStateException("job_preferences row 1 is missing; V7 seeds it."));
    }
}
