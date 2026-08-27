package se.caiowain.jobseeker.profile.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.profile.domain.CvDocument;

import java.util.Optional;

public interface CvDocumentRepository extends JpaRepository<CvDocument, Long> {
    Optional<CvDocument> findBySha256(String sha256);
}
