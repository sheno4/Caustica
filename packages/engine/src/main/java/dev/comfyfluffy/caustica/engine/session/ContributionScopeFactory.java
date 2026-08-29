package dev.comfyfluffy.caustica.engine.session;

/** Creates the renderer services owned by one contribution in one render session. */
@FunctionalInterface
public interface ContributionScopeFactory {
    ContributionScope create(ContributionOwner owner);
}
