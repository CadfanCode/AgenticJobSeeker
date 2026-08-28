package se.caiowain.jobseeker.profile.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.util.Optional;

public interface CvProfileRepository extends JpaRepository<CvProfile, Long> {
    /** The newest profile is the current one. */
    Optional<CvProfile> findFirstByOrderByIdDesc();
    Optional<CvProfile> findFirstByCvDocumentIdOrderByIdDesc(Long cvDocumentId);
}
