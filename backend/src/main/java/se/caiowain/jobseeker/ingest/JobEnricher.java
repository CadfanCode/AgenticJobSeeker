package se.caiowain.jobseeker.ingest;

import java.util.Optional;

/**
 * A source that cannot enumerate jobs, only fetch one whose URL is already known.
 *
 * <p>ReachMee is the motivating case: it serves from shared hosts behind per-tenant
 * signed {@code validator} URLs, so its catalogue cannot be listed. Implementations
 * are deferred to a later slice; the port exists so they have a home.
 */
public interface JobEnricher {

    boolean supports(String applyUrl);

    Optional<RawJob> enrich(String applyUrl);
}
