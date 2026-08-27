package se.caiowain.jobseeker.api;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class JobQueryService {

    private final JobPostingRepository postings;

    public JobQueryService(JobPostingRepository postings) {
        this.postings = postings;
    }

    public Page<JobPosting> search(String query, String municipality, AtsVendor vendor,
                                   SourceId source, int page, int size) {
        Specification<JobPosting> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern),
                        cb.like(cb.lower(root.get("employerName")), pattern)));
            }
            if (municipality != null && !municipality.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("municipality")), municipality.toLowerCase()));
            }
            if (vendor != null) {
                predicates.add(cb.equal(root.get("atsVendor"), vendor));
            }
            if (source != null) {
                criteriaQuery.distinct(true);
                predicates.add(cb.equal(root.join("sources", JoinType.INNER).get("source"), source));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        return postings.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt")));
    }
}
