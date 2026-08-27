package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import se.caiowain.jobseeker.domain.JobPosting;

import java.util.Optional;

public interface JobPostingRepository
        extends JpaRepository<JobPosting, Long>, JpaSpecificationExecutor<JobPosting> {
    Optional<JobPosting> findByFingerprint(String fingerprint);
    Optional<JobPosting> findByCanonicalUrl(String canonicalUrl);
    java.util.List<JobPosting> findByEmployerJobKey(String employerJobKey);
}
