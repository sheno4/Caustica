package dev.comfyfluffy.caustica.engine.program;

import dev.comfyfluffy.caustica.api.program.ProgramBuilder;
import dev.comfyfluffy.caustica.api.program.ProgramChannel;
import dev.comfyfluffy.caustica.api.program.ProgramRegistration;
import dev.comfyfluffy.caustica.engine.session.ContributionOwner;

import java.util.function.Function;

/** Owner-scoped view of a render session's composed program. */
public final class ProgramContributionChannel implements ProgramChannel {
    final ProgramSession session;
    final ContributionOwner owner;
    boolean accepting = true;

    ProgramContributionChannel(ProgramSession session, ContributionOwner owner) {
        this.session = session;
        this.owner = owner;
    }

    @Override
    public <E> ProgramRegistration<E> register(Function<? super ProgramBuilder, ? extends E> declaration) {
        return session.register(this, declaration);
    }

    /** Rejects future declarations without disturbing registrations already accepted. */
    public void quiesce() {
        session.quiesce(this);
    }

    /** Closes every registration owned by this contribution. */
    public void invalidate() {
        session.invalidate(this);
    }

    /** Advances renderer publication until this owner's accepted work and callbacks have drained. */
    public void drain() {
        session.drain(this);
    }
}
