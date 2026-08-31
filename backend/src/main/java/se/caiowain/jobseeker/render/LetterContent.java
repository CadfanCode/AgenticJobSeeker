package se.caiowain.jobseeker.render;

import java.time.LocalDate;

/**
 * @param body the prose the candidate wrote. Empty is legitimate — see the spec's note on an
 *             empty letter body; the letter still renders and is still archived.
 */
public record LetterContent(String candidateName, String candidateEmail, String candidatePhone,
                            String candidateLocation, String employerName, String jobTitle,
                            LocalDate date, String body) {
}
