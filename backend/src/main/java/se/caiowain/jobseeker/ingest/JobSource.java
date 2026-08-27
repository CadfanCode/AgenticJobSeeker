package se.caiowain.jobseeker.ingest;

import se.caiowain.jobseeker.domain.SourceId;

import java.util.List;

/** A source that can enumerate jobs on its own. */
public interface JobSource {

    SourceId id();

    /**
     * Fetches jobs matching any of the given criteria. Implementations that support
     * server-side filtering (JobTech) push the criteria into the request; implementations
     * reading whole-catalogue feeds (Teamtailor, Varbi) fetch everything and filter with
     * {@link SearchCriteriaSpec#matchesLocally(RawJob)}.
     */
    List<RawJob> fetch(List<SearchCriteriaSpec> criteria);
}
