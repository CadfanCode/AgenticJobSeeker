package se.caiowain.jobseeker.tailor.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.util.Optional;

public interface TailoredApplicationRepository extends JpaRepository<TailoredApplication, Long> {
    Optional<TailoredApplication> findByJobPostingIdAndCvProfileId(Long jobPostingId, Long cvProfileId);
    Optional<TailoredApplication> findFirstByJobPostingIdOrderByIdDesc(Long jobPostingId);
    Page<TailoredApplication> findAllByOrderByGeneratedAtDesc(Pageable pageable);
}
