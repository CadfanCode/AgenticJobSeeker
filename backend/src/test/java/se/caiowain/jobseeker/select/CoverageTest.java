package se.caiowain.jobseeker.select;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageTest {

    @Test
    void noRequirementsIsZeroRatherThanADivideByZero() {
        assertThat(Coverage.percent(0, 0)).isZero();
    }

    @Test
    void everyRequirementCoveredIsOneHundred() {
        assertThat(Coverage.percent(5, 5)).isEqualTo(100);
    }

    @Test
    void nothingCoveredIsZero() {
        assertThat(Coverage.percent(0, 7)).isZero();
    }

    @Test
    void roundsToTheNearestWholePercent() {
        // 2/3 = 66.67 -> 67. Rounding, not truncation: truncation would report 66,
        // which reads as further from the job than the evidence actually is.
        assertThat(Coverage.percent(2, 3)).isEqualTo(67);
        assertThat(Coverage.percent(1, 3)).isEqualTo(33);
    }
}
