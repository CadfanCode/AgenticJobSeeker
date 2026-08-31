package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;

import java.util.Optional;

public interface JobDeepFitRepository extends JpaRepository<JobDeepFit, Long> {
    Optional<JobDeepFit> findByJobPostingIdAndCvProfileId(Long jobPostingId, Long cvProfileId);
}
