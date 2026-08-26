package dev.comfyfluffy.caustica.api;

import java.util.List;

/**
 * The engine slots a composition binds exactly one feature to.
 *
 * <p>There are none. A slot exists where the engine has one role and implementations compete for it;
 * every role the API currently exposes is instead a registered <em>set</em> whose member is named by
 * whatever uses it — a material names its {@code ISurfaceModel} (see {@link FeatureBuilder#surface}), a
 * scene names its {@code IEnvironmentModel} (see {@link FeatureBuilder#environment}) — so nothing competes
 * and two of them can be live at once.
 */
public final class Slots {
    public static final List<Slot> ALL = List.of();

    private Slots() {
    }
}
