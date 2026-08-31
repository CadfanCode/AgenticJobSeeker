package se.caiowain.jobseeker.select;

/**
 * How much of a job ad the candidate's own bullets actually answer.
 *
 * <p>Shared by tailoring and fit scoring so the two cannot drift. This number is computed
 * in Java from counted rows — it is never produced by a model, and it is not a judgement
 * about the candidate. Requirements with no evidence are the useful half of it.
 */
public final class Coverage {

    private Coverage() {
    }

    public static int percent(int withEvidence, int total) {
        return total == 0 ? 0 : Math.round(withEvidence * 100f / total);
    }
}
