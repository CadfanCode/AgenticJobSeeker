package se.caiowain.jobseeker.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import se.caiowain.jobseeker.domain.SearchCriteria;

import java.util.List;

public interface SearchCriteriaRepository extends JpaRepository<SearchCriteria, Long> {
    List<SearchCriteria> findByEnabledTrue();
}
