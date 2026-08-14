package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.Test;

import static dev.comfyfluffy.caustica.rt.accel.RtAccel.RefitDecision.BUILD;
import static dev.comfyfluffy.caustica.rt.accel.RtAccel.RefitDecision.REFIT;
import static dev.comfyfluffy.caustica.rt.accel.RtAccel.refitDecision;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtAccelRefitDecisionTest {
    @Test
    void refitsWhenEverythingAgreesAndUnderTheInterval() {
        assertEquals(REFIT, refitDecision(true, true, true, true, 0, 120));
        assertEquals(REFIT, refitDecision(true, true, true, true, 119, 120));
    }

    @Test
    void firstBuildHasNoAccelToRefit() {
        assertEquals(BUILD, refitDecision(true, false, true, true, 0, 120));
    }

    @Test
    void disabledRefitAlwaysBuildsRegardlessOfOtherSignals() {
        assertEquals(BUILD, refitDecision(false, true, true, true, 0, 120));
    }

    @Test
    void slotNotBuiltWithAllowUpdateCannotBeRefit() {
        assertEquals(BUILD, refitDecision(true, true, false, true, 0, 120));
    }

    @Test
    void topologyChangeForcesABuildEvenWellUnderTheInterval() {
        assertEquals(BUILD, refitDecision(true, true, true, false, 0, 120));
    }

    @Test
    void intervalReachedForcesAPeriodicQualityRebuild() {
        assertEquals(BUILD, refitDecision(true, true, true, true, 120, 120));
        assertEquals(BUILD, refitDecision(true, true, true, true, 121, 120));
    }
}
