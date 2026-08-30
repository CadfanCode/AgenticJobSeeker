package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;

import java.util.Optional;

public interface JobPrescreenRepository extends JpaRepository<JobPrescreen, Long> {
    Optional<JobPrescreen> findByJobPostingId(Long jobPostingId);
}
