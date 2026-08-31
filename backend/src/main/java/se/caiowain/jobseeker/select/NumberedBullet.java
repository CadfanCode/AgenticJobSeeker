package se.caiowain.jobseeker.select;

/**
 * A CV bullet as presented to the model.
 *
 * @param index    the 1..N number shown in the prompt
 * @param bulletId the real profile bullet id, never shown to the model
 */
public record NumberedBullet(int index, Long bulletId, String text) {
}
