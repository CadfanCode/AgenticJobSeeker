package se.caiowain.jobseeker.fit.domain;

/**
 * Declared in increasing order, and the order is load-bearing.
 *
 * <p>Real postings and real CVs describe language ability in incompatible vocabularies —
 * CEFR letters, LinkedIn buckets, plain words like "conversational". A human can reason
 * about that ambiguity; a deterministic gate cannot. So the levels a person selects are
 * a fixed ordered scale, and ad phrasings are mapped onto it explicitly.
 */
public enum LanguageLevel {
    NONE, BASIC, CONVERSATIONAL, PROFESSIONAL, FLUENT, NATIVE;

    public boolean atLeast(LanguageLevel required) {
        return compareTo(required) >= 0;
    }
}
