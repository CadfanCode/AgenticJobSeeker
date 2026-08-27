package se.caiowain.jobseeker.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.IngestRun;

public interface IngestRunRepository extends JpaRepository<IngestRun, Long> {
    Page<IngestRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
