package dev.comfyfluffy.caustica.api.program;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProgramContractTest {
    @Test
    void cancelledIsATerminalCompletionKind() {
        ProgramTicket.Completion completion = new ProgramTicket.Cancelled();

        assertInstanceOf(ProgramTicket.Cancelled.class, completion);
        assertSame(ProgramTicket.State.CANCELLED, ProgramTicket.State.valueOf("CANCELLED"));
    }

    @Test
    void programCompositionExposesVolumesWithoutGlobalSurfaceModifiers() throws NoSuchMethodException {
        assertSame(ProgramUpdate.class,
                ProgramChannel.class.getMethod("addVolume", VolumeDefinition.class).getReturnType());
        assertSame(ProgramTicket.class,
                ProgramChannel.class.getMethod("dropVolume", VolumeId.class, Runnable.class).getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> ProgramChannel.class.getMethod("addSurfaceModifier", ShaderDefinition.class));
    }
}
