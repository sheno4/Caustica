package dev.comfyfluffy.caustica.api.program;

import java.util.Objects;

/** An id usable immediately and the non-blocking readiness of the program which implements it. */
public record ProgramUpdate<T>(T id, ProgramTicket ticket) {
    public ProgramUpdate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ticket, "ticket");
    }
}
