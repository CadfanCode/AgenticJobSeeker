package se.caiowain.jobseeker.tailor.select;

import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.util.ArrayList;
import java.util.List;

/** Pure prompt construction. No Spring, no I/O. */
public class TailoringPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You match a candidate's CV bullets to a job ad.

            You NEVER write new claims about the candidate. You only return:
              - bullet numbers, chosen from the numbered list you are given
              - requirement phrases copied VERBATIM from the job ad

            Rules:
            - Copy requirement phrases exactly as written in the ad. Do not translate,
              summarise or rephrase them.
            - Only use bullet numbers that appear in the list. Never invent a number.
            - A requirement should cite at most 3 bullets, and only genuinely relevant ones.
              If nothing in the CV supports a requirement, return an empty bulletIds list —
              that is a useful answer, not a failure.
            - Respond with JSON only. No commentary, no markdown fences.
            """;

    /** Flattens the profile's bullets into a dense 1..N list for the prompt. */
    public List<NumberedBullet> numberBullets(CvProfile profile) {
        List<NumberedBullet> numbered = new ArrayList<>();
        int index = 1;
        for (CvExperience experience : profile.getExperiences()) {
            for (CvExperienceBullet bullet : experience.getBullets()) {
                if (bullet.getText() == null || bullet.getText().isBlank()) {
                    continue;
                }
                numbered.add(new NumberedBullet(index++, bullet.getId(), bullet.getText().strip()));
            }
        }
        return numbered;
    }

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(String jobDescription, List<NumberedBullet> bullets) {
        StringBuilder listing = new StringBuilder();
        for (NumberedBullet bullet : bullets) {
            listing.append(bullet.index()).append(". ").append(bullet.text()).append('\n');
        }
        return """
                JOB AD:
                %s

                CANDIDATE CV BULLETS:
                %s
                Return JSON exactly of this shape:
                {"requirements":[{"text":"<phrase copied verbatim from the ad>","bulletIds":[<numbers>]}],\
                "rankedBulletIds":[<relevant numbers, most relevant first>]}
                """.formatted(jobDescription, listing);
    }
}
