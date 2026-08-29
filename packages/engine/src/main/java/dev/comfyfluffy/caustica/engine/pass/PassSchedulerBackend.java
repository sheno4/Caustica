package dev.comfyfluffy.caustica.engine.pass;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;

/** Renderer-owned frame scheduling and post-chain boundary used by the pass service. */
public interface PassSchedulerBackend {
    WorldResourceSetup worldResourceSetup();

    PostEffectSetup postEffectSetup();

    UiSetup uiSetup();

    Invocation<PassFrame> beginWorldResource(PassKey pass);

    PostInvocation beginPostEffect(PassKey pass);

    Invocation<UiFrame> beginUi(PassKey pass);

    /**
     * One borrowed frame invocation. Completion invokes {@code drained} on any thread after its GPU use
     * and frame retirement callbacks can no longer execute.
     */
    interface Invocation<F extends PassFrame> {
        F frame();

        void submit(Runnable drained);

        void abandon(Throwable failure, Runnable drained);
    }

    /** Post-effect invocation whose renderer validates and advances the current scene-colour chain. */
    interface PostInvocation extends Invocation<PostEffectFrame> {
        /** Validate this pass's acquisition against the current chain and publish its output if acquired. */
        void validateOutputChain();
    }
}
