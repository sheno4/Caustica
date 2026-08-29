package dev.comfyfluffy.caustica.engine.pass;

import dev.comfyfluffy.caustica.api.pass.PassChannel;
import dev.comfyfluffy.caustica.api.pass.PassFactory;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PassPlacement;
import dev.comfyfluffy.caustica.api.pass.PassRegistration;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;

/** Owner-scoped view of the passes in one render session. */
public final class PassContributionChannel implements PassChannel {
    final PassSession session;
    final Object owner;
    boolean accepting = true;

    PassContributionChannel(PassSession session, Object owner) {
        this.session = session;
        this.owner = owner;
    }

    @Override
    public PassRegistration addWorldResourcePass(PassFactory<WorldResourceSetup, PassFrame> factory) {
        return session.addWorldResource(this, factory);
    }

    @Override
    public PassRegistration addPostEffectPass(
            PassId id, PassFactory<PostEffectSetup, PostEffectFrame> factory) {
        return session.addPostEffect(this, id, null, factory);
    }

    @Override
    public PassRegistration addPostEffectPass(
            PassId id, PassPlacement placement, PassFactory<PostEffectSetup, PostEffectFrame> factory) {
        return session.addPostEffect(this, id, placement, factory);
    }

    @Override
    public PassRegistration addUiPass(PassId id, PassFactory<UiSetup, UiFrame> factory) {
        return session.addUi(this, id, null, factory);
    }

    @Override
    public PassRegistration addUiPass(
            PassId id, PassPlacement placement, PassFactory<UiSetup, UiFrame> factory) {
        return session.addUi(this, id, placement, factory);
    }

    /** Rejects new registrations, stops future callbacks, and waits for callbacks already running. */
    public void quiesce() {
        session.quiesce(this);
    }

    /** Removes every registration owned by this contribution. */
    public void invalidate() {
        session.invalidate(this);
    }

    /** Waits for this contribution's frame uses and pass-instance closes. */
    public void drain() {
        session.drain(this);
    }
}
