package se.caiowain.jobseeker.api;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobPostingSource;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.util.ArrayList;
import java.util.List;

@Service
public class JobQueryService {

    private final JobPostingRepository postings;

    public JobQueryService(JobPostingRepository postings) {
        this.postings = postings;
    }

    /**
     * @param sort                 {@code "skills"} ranks by how many of your skills the ad
     *                             names; anything else keeps newest-first
     * @param triage               show only this decision; null shows everything except
     *                             dismissed
     * @param includeGateFailures  when false, postings a gate vetoed are sorted out of view.
     *                             They are never deleted, and this flag brings them back.
     */
    public Page<JobPosting> search(String query, String municipality, AtsVendor vendor,
                                   SourceId source, String sort, TriageState triage,
                                   boolean includeGateFailures, int page, int size) {

        Specification<JobPosting> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Ad-hoc entity joins (JPA 3.2) rather than mapped associations. JobPosting
            // deliberately has no inverse @OneToOne to these tables: a mappedBy @OneToOne
            // cannot be a true lazy proxy without bytecode enhancement, so mapping them made
            // Hibernate resolve both on every JobPosting load anywhere in the application —
            // measured at ~1,200 extra queries per prescreen run over ~600 postings.
            //
            // LEFT, always: a posting ingested since the last prescreen has no fit row and
            // must still be listed. An inner join here would silently hide new jobs.
            Join<JobPosting, JobPrescreen> fit = root.join(JobPrescreen.class, JoinType.LEFT);
            fit.on(cb.equal(fit.get("jobPosting"), root));
            Join<JobPosting, JobTriage> decision = root.join(JobTriage.class, JoinType.LEFT);
            decision.on(cb.equal(decision.get("jobPosting"), root));

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern),
                        cb.like(cb.lower(root.get("employerName")), pattern)));
            }
            if (municipality != null && !municipality.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("municipality")),
                        municipality.toLowerCase()));
            }
            if (vendor != null) {
                predicates.add(cb.equal(root.get("atsVendor"), vendor));
            }
            if (source != null) {
                // EXISTS rather than a join plus distinct. DISTINCT would collide with the
                // ORDER BY below, because PostgreSQL requires ordering expressions to appear
                // in the select list of a SELECT DISTINCT.
                Subquery<Long> sub = criteriaQuery.subquery(Long.class);
                Root<JobPostingSource> sourceRoot = sub.from(JobPostingSource.class);
                sub.select(cb.literal(1L)).where(cb.and(
                        cb.equal(sourceRoot.get("jobPosting"), root),
                        cb.equal(sourceRoot.get("source"), source)));
                predicates.add(cb.exists(sub));
            }

            if (triage == TriageState.NEW) {
                // An undecided posting has no triage row at all, not a row whose state is
                // NEW — so matching only an explicit NEW row would return almost nothing.
                predicates.add(cb.or(
                        decision.get("state").isNull(),
                        cb.equal(decision.get("state"), TriageState.NEW)));
            } else if (triage != null) {
                predicates.add(cb.equal(decision.get("state"), triage));
            } else {
                // Undecided jobs have no row at all, so the null case is the common one.
                predicates.add(cb.or(
                        decision.get("state").isNull(),
                        cb.notEqual(decision.get("state"), TriageState.DISMISSED)));
            }

            if (!includeGateFailures) {
                predicates.add(cb.or(fit.get("languageGate").isNull(),
                        cb.notEqual(fit.get("languageGate"), GateVerdict.FAIL)));
                predicates.add(cb.or(fit.get("locationGate").isNull(),
                        cb.notEqual(fit.get("locationGate"), GateVerdict.FAIL)));
                predicates.add(cb.or(fit.get("deadlinePassed").isNull(),
                        cb.isFalse(fit.get("deadlinePassed"))));
            }

            // Ordering lives here rather than in the Pageable because it has to reference
            // the LEFT join above; a Sort on the nested path would make Hibernate build its
            // own inner join and drop every posting without a prescreen row.
            //
            // Spring Data reuses this Specification for its count query, and PostgreSQL
            // rejects ORDER BY in a count, so skip it when the result type is a Long.
            Class<?> resultType = criteriaQuery.getResultType();
            if (resultType != Long.class && resultType != long.class) {
                if ("skills".equals(sort)) {
                    criteriaQuery.orderBy(
                            cb.desc(cb.coalesce(fit.<Integer>get("matchedSkillCount"), 0)),
                            cb.desc(root.get("publishedAt")));
                } else {
                    criteriaQuery.orderBy(cb.desc(root.get("publishedAt")));
                }
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };

        // Unsorted Pageable: the Specification above owns the ordering.
        return postings.findAll(spec, PageRequest.of(page, size));
    }
}
