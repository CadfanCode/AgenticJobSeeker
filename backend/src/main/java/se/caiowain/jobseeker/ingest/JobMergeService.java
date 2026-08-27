package se.caiowain.jobseeker.ingest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.JobPostingSource;
import se.caiowain.jobseeker.domain.JobStatus;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Implements the spec's merge policy: a duplicate is never discarded. It becomes an
 * additional {@link JobPostingSource} row and may upgrade the canonical posting's
 * description and apply URL.
 */
@Service
public class JobMergeService {

    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;

    public JobMergeService(JobPostingRepository postings, JobPostingSourceRepository sources) {
        this.postings = postings;
        this.sources = sources;
    }

    @Transactional
    public MergeOutcome ingest(SourceId source, RawJob raw, AtsVendor vendor) {
        // Layer 1: natural key. Already seen from this exact source.
        Optional<JobPostingSource> existingSource =
                sources.findBySourceAndSourceAdId(source, raw.sourceAdId());
        if (existingSource.isPresent()) {
            JobPosting posting = existingSource.get().getJobPosting();
            posting.setLastSeenAt(Instant.now());
            postings.save(posting);
            return MergeOutcome.UNCHANGED;
        }

        String fingerprint = JobIdentity.fingerprint(
                raw.employerOrgNumber(), raw.title(), raw.description());
        String employerJobKey = JobIdentity.employerJobKey(raw.applyUrl(), raw.sourceUrl());

        // Layer 2 first: the employer-side job id is the only link that survives two
        // sources describing the same posting in different words.
        Optional<JobPosting> existing = Optional.empty();
        if (employerJobKey != null) {
            existing = postings.findByEmployerJobKey(employerJobKey).stream()
                    // Guard against employers that publish one generic apply URL.
                    .filter(p -> JobIdentity.normalizeTitle(p.getTitle())
                            .equals(JobIdentity.normalizeTitle(raw.title())))
                    .findFirst();
        }
        // Layer 3: identical content, for sources whose URLs carry no job id.
        if (existing.isEmpty()) {
            existing = postings.findByFingerprint(fingerprint);
        }
        if (existing.isEmpty()) {
            String canonical = JobIdentity.canonicalUrl(raw.sourceUrl());
            if (canonical != null) {
                existing = postings.findByCanonicalUrl(canonical);
            }
        }

        if (existing.isPresent()) {
            JobPosting posting = existing.get();
            upgrade(posting, raw, vendor);
            postings.save(posting);
            attachSource(posting, source, raw);
            return MergeOutcome.MERGED;
        }

        JobPosting posting = create(raw, vendor, fingerprint, employerJobKey);
        postings.save(posting);
        attachSource(posting, source, raw);
        return MergeOutcome.CREATED;
    }

    private JobPosting create(RawJob raw, AtsVendor vendor, String fingerprint, String employerJobKey) {
        Instant now = Instant.now();
        JobPosting posting = new JobPosting();
        posting.setFingerprint(fingerprint);
        posting.setCanonicalUrl(JobIdentity.canonicalUrl(raw.sourceUrl()));
        posting.setEmployerJobKey(employerJobKey);
        posting.setTitle(raw.title());
        posting.setEmployerName(raw.employerName());
        posting.setEmployerOrgNumber(raw.employerOrgNumber());
        posting.setMunicipality(raw.municipality());
        posting.setDescription(raw.description());
        posting.setLanguage(raw.language());
        posting.setAtsVendor(vendor == null ? AtsVendor.OTHER : vendor);
        posting.setApplyUrl(JobIdentity.canonicalUrl(raw.applyUrl()));
        posting.setPublishedAt(raw.publishedAt());
        posting.setDeadlineAt(raw.deadlineAt());
        posting.setStatus(JobStatus.DISCOVERED);
        posting.setFirstSeenAt(now);
        posting.setLastSeenAt(now);
        return posting;
    }

    /** Richest description wins; a non-null apply URL and vendor fill gaps. */
    private void upgrade(JobPosting posting, RawJob raw, AtsVendor vendor) {
        String incoming = raw.description();
        String current = posting.getDescription();
        if (incoming != null && (current == null || incoming.length() > current.length())) {
            posting.setDescription(incoming);
        }
        if (raw.applyUrl() != null && !raw.applyUrl().isBlank()) {
            posting.setApplyUrl(JobIdentity.canonicalUrl(raw.applyUrl()));
        }
        if (posting.getEmployerJobKey() == null) {
            posting.setEmployerJobKey(JobIdentity.employerJobKey(raw.applyUrl(), raw.sourceUrl()));
        }
        if (posting.getEmployerOrgNumber() == null && raw.employerOrgNumber() != null) {
            posting.setEmployerOrgNumber(raw.employerOrgNumber());
        }
        if (posting.getMunicipality() == null && raw.municipality() != null) {
            posting.setMunicipality(raw.municipality());
        }
        if (vendor != null && vendor != AtsVendor.OTHER) {
            posting.setAtsVendor(vendor);
        }
        posting.setLastSeenAt(Instant.now());
    }

    private void attachSource(JobPosting posting, SourceId source, RawJob raw) {
        JobPostingSource row = new JobPostingSource();
        row.setJobPosting(posting);
        row.setSource(source);
        row.setSourceAdId(raw.sourceAdId());
        row.setSourceUrl(raw.sourceUrl());
        row.setRawPayload(raw.rawPayload());
        row.setFetchedAt(Instant.now());
        sources.save(row);
    }
}
