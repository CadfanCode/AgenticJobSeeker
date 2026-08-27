package se.caiowain.jobseeker.ingest;

public enum MergeOutcome {
    /** A new canonical posting was created. */
    CREATED,
    /** An existing posting gained an additional source row. */
    MERGED,
    /** This exact (source, adId) pair was already stored; nothing changed. */
    UNCHANGED
}
