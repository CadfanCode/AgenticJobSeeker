package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.JobPostingSource;
import se.caiowain.jobseeker.domain.SourceId;

import java.util.List;
import java.util.Optional;

public interface JobPostingSourceRepository extends JpaRepository<JobPostingSource, Long> {
    Optional<JobPostingSource> findBySourceAndSourceAdId(SourceId source, String sourceAdId);
    List<JobPostingSource> findByJobPostingId(Long jobPostingId);
    long countBySource(SourceId source);
}
