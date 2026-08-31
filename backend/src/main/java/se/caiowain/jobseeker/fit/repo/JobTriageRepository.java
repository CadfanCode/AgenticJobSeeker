package se.caiowain.jobseeker.fit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.fit.domain.JobTriage;

import java.util.Optional;

public interface JobTriageRepository extends JpaRepository<JobTriage, Long> {
    Optional<JobTriage> findByJobPostingId(Long jobPostingId);

    java.util.List<se.caiowain.jobseeker.fit.domain.JobTriage>
            findByJobPostingIdIn(java.util.Collection<Long> jobPostingIds);
}
