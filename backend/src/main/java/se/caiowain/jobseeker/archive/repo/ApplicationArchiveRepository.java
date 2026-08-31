package se.caiowain.jobseeker.archive.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;

public interface ApplicationArchiveRepository extends JpaRepository<ApplicationArchive, Long> {
    Page<ApplicationArchive> findAllByOrderByApprovedAtDesc(Pageable pageable);
}
