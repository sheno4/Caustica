package dev.comfyfluffy.caustica.api.program;

/**
 * A non-owning environment implementation reference compiled into one render session's world program and
 * issued by {@link ProgramBuilder#environment}. It may be shared across contributions in that session so
 * their scenes can bind the same implementation; the issuing {@link ProgramRegistration} retains removal
 * authority and owns its lifetime. The identity is opaque and meaningful only within that render session.
 *
 * @param <B> required scene-binding data schema
 */
public interface EnvironmentId<B> {
}
