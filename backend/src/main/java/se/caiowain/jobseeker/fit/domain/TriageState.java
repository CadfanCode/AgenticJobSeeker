package se.caiowain.jobseeker.fit.domain;

/**
 * What you decided about a posting.
 *
 * <p>{@code SHORTLISTED} means only "worth my attention" — it asserts nothing about an
 * application. Tailoring has its own separate status vocabulary (Slice 2b).
 */
public enum TriageState { NEW, SHORTLISTED, DISMISSED }
