package se.caiowain.jobseeker.tailor;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.select.NumberedBullet;
import se.caiowain.jobseeker.tailor.select.SelectionGuard;
import se.caiowain.jobseeker.tailor.select.SelectionResult;
import se.caiowain.jobseeker.tailor.select.SelectionResult.RequirementSelection;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationAssemblerTest {

    private final ApplicationAssembler assembler = new ApplicationAssembler();

    private final List<NumberedBullet> bullets = List.of(
            new NumberedBullet(1, 101L, "Built REST APIs in Java and Spring Boot."),
            new NumberedBullet(2, 102L, "Reduced deployment time from hours to minutes."),
            new NumberedBullet(3, 103L, "Ran the public developer API and its documentation."));

    private SelectionGuard.GuardVerdict clean() {
        return new SelectionGuard.GuardVerdict(List.of(), Set.of());
    }

    private TailoredApplication assemble(SelectionResult result, SelectionGuard.GuardVerdict verdict) {
        return assembler.assemble(result, verdict, bullets, new JobPosting(), new CvProfile(),
                "qwen2.5:7b-instruct", "{}");
    }

    @Test
    void everyAssembledBulletIsByteIdenticalToAProfileBullet() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 3))),
                List.of(1, 3));

        TailoredApplication app = assemble(result, clean());

        var evidence = app.getRequirements().getFirst().getEvidence();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).getBulletText()).isEqualTo("Built REST APIs in Java and Spring Boot.");
        assertThat(evidence.get(1).getBulletText())
                .isEqualTo("Ran the public developer API and its documentation.");
    }

    @Test
    void evidenceCarriesTheRealProfileBulletId() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(3))), List.of(3));

        assertThat(assemble(result, clean()).getRequirements().getFirst()
                .getEvidence().getFirst().getCvExperienceBulletId()).isEqualTo(103L);
    }

    @Test
    void coverageIsRequirementsWithEvidenceOverTotal() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1)),
                new RequirementSelection("Har god förståelse API-design", List.of()),
                new RequirementSelection("Har mycket god engelska i tal och skrift", List.of(2)),
                new RequirementSelection("Har en avslutad eftergymnasial utbildning", List.of())),
                List.of(1, 2));

        assertThat(assemble(result, clean()).getCoveragePercent()).isEqualTo(50);
    }

    @Test
    void coverageIsZeroWhenNothingMatches() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of())), List.of());

        assertThat(assemble(result, clean()).getCoveragePercent()).isZero();
    }

    @Test
    void overBroadRequirementsAreMarked() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 2, 3))),
                List.of(1, 2, 3));

        var verdict = new SelectionGuard.GuardVerdict(List.of(), Set.of(0));

        assertThat(assemble(result, verdict).getRequirements().getFirst().isOverBroad()).isTrue();
    }

    @Test
    void unknownBulletNumbersAreSkippedRatherThanInvented() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1, 99))),
                List.of(1));

        assertThat(assemble(result, clean()).getRequirements().getFirst().getEvidence()).hasSize(1);
    }

    @Test
    void requirementOrderAndStatusAreSet() {
        var result = new SelectionResult(List.of(
                new RequirementSelection("Har erfarenhet av webbutveckling.", List.of(1)),
                new RequirementSelection("Har god förståelse API-design", List.of(3))),
                List.of(1, 3));

        TailoredApplication app = assemble(result, clean());

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);
        assertThat(app.getModelUsed()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(app.getGeneratedAt()).isNotNull();
        assertThat(app.getRequirements().get(0).getOrdinal()).isZero();
        assertThat(app.getRequirements().get(1).getOrdinal()).isEqualTo(1);
    }
}
